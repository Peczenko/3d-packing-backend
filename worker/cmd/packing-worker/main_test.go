package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/config"
	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

// The exit codes are asserted as literals throughout, never as the constants
// they pin. An assertion written against exitProcessed would survive any
// renumbering of the table, which is the one thing these tests exist to catch.
const (
	testJobID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"

	// testSpecMarker and testSecretMarker are unique strings planted in the
	// two places a leak would come from: the packing spec inside the request
	// envelope, and the connection strings in the environment. No log line may
	// contain either.
	testSpecMarker   = "SPEC-MUST-NEVER-BE-LOGGED"
	testSecretMarker = "SHARED-KEY-MUST-NEVER-BE-LOGGED"

	testEngineVersion  = "packer 0.4.1"
	testEngineChecksum = "1111111111111111111111111111111111111111111111111111111111111111"
	testResultChecksum = "2222222222222222222222222222222222222222222222222222222222222222"
)

var testConnectionString = "Endpoint=sb://fake.servicebus.windows.net/;SharedAccessKeyName=worker;SharedAccessKey=" + testSecretMarker

func dispatchBody() []byte {
	return []byte(`{"messageVersion":1,"jobId":"` + testJobID + `"}`)
}

func requestBody() []byte {
	return []byte(`{"requestVersion":1,"maxRuntimeSeconds":30,"spec":{"label":"` + testSpecMarker + `"}}`)
}

// useTestEnvironment sets every variable config.Load reads, so one that
// happens to be set on the machine running the test cannot change what is
// loaded. Every value is deliberately different from the package default: a
// fixture equal to the default would let an assertion pass even if the value
// never travelled from the environment to the worker.
func useTestEnvironment(t *testing.T, workRoot, lockRenewInterval string) {
	t.Helper()
	t.Setenv("SERVICE_BUS_CONNECTION_STRING", testConnectionString)
	t.Setenv("SERVICE_BUS_NAMESPACE", "")
	t.Setenv("STORAGE_CONNECTION_STRING", testConnectionString)
	t.Setenv("STORAGE_ACCOUNT_URL", "")
	t.Setenv("PACKING_DISPATCH_QUEUE", "test-dispatch")
	t.Setenv("PACKING_RESULT_QUEUE", "test-results")
	t.Setenv("STORAGE_CONTAINER_NAME", "test-models")
	t.Setenv("PACKER_PATH", "/test/bin/packer")
	t.Setenv("PACKING_WORK_ROOT", workRoot)
	t.Setenv("PACKING_RECEIVE_TIMEOUT", "5s")
	t.Setenv("PACKING_LOCK_RENEW_INTERVAL", lockRenewInterval)
}

type fakeHandler struct {
	err error

	calls  int
	ctxErr error
}

func (h *fakeHandler) RunOnce(ctx context.Context) error {
	h.calls++
	h.ctxErr = ctx.Err()
	return h.err
}

type connectorSpy struct {
	calls       int
	cfg         config.Config
	closes      int
	closeCtxErr error
}

func (s *connectorSpy) serving(handler oneShot, closeErr error) connector {
	return func(cfg config.Config, _ *logger) (oneShot, func(context.Context) error, error) {
		s.calls++
		s.cfg = cfg
		return handler, func(ctx context.Context) error {
			s.closes++
			s.closeCtxErr = ctx.Err()
			return closeErr
		}, nil
	}
}

func (s *connectorSpy) failing(err error) connector {
	return func(cfg config.Config, _ *logger) (oneShot, func(context.Context) error, error) {
		s.calls++
		s.cfg = cfg
		return nil, nil, err
	}
}

// processing builds the real pipeline.Processor over fake ports, wired the way
// connectAzure wires the Azure ones. The exit codes that matter most —
// engine failure, lost lock — are only meaningful against the processor's own
// settlement rules, so they are asserted against the real state machine.
func processing(queue pipeline.Queue, artifacts pipeline.Artifacts, engine pipeline.Engine) connector {
	return func(cfg config.Config, log *logger) (oneShot, func(context.Context) error, error) {
		processor := pipeline.NewProcessor(
			&observedQueue{inner: queue, log: log},
			artifacts,
			engine,
			cfg.WorkRoot,
			cfg.LockRenewInterval,
		)
		return processor, func(context.Context) error { return nil }, nil
	}
}

// fakeDelivery is handed out and settled by pointer, so the queue below can
// check identity the way the real one does — azureio settles by asserting the
// delivery is its own and rejects anything else as a foreign delivery.
type fakeDelivery struct {
	body []byte
}

func (d *fakeDelivery) Body() []byte { return d.body }

type fakeQueue struct {
	delivery   pipeline.Delivery
	receiveErr error
	renewErr   error

	mu          sync.Mutex
	events      []contracts.WorkerEvent
	settlements []string
}

func (q *fakeQueue) ReceiveOne(context.Context) (pipeline.Delivery, error) {
	if q.receiveErr != nil {
		return nil, q.receiveErr
	}
	return q.delivery, nil
}

func (q *fakeQueue) RenewLock(_ context.Context, delivery pipeline.Delivery) error {
	if err := q.own(delivery); err != nil {
		return err
	}
	return q.renewErr
}

// own rejects a delivery this queue did not issue, exactly as the Service Bus
// adapter does. It is what makes a composition root that wrapped the delivery
// on its way through fail here instead of in production.
func (q *fakeQueue) own(delivery pipeline.Delivery) error {
	if delivery != q.delivery {
		return fmt.Errorf("fakeQueue: foreign delivery %T", delivery)
	}
	return nil
}

func (q *fakeQueue) SendEvent(_ context.Context, event contracts.WorkerEvent) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.events = append(q.events, event)
	return nil
}

func (q *fakeQueue) Complete(_ context.Context, delivery pipeline.Delivery) error {
	if err := q.own(delivery); err != nil {
		return err
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	q.settlements = append(q.settlements, "complete")
	return nil
}

func (q *fakeQueue) Abandon(_ context.Context, delivery pipeline.Delivery) error {
	if err := q.own(delivery); err != nil {
		return err
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	q.settlements = append(q.settlements, "abandon")
	return nil
}

func (q *fakeQueue) eventTypes() []string {
	q.mu.Lock()
	defer q.mu.Unlock()
	types := make([]string, 0, len(q.events))
	for _, event := range q.events {
		types = append(types, event.EventType)
	}
	return types
}

func (q *fakeQueue) settled() []string {
	q.mu.Lock()
	defer q.mu.Unlock()
	return append([]string(nil), q.settlements...)
}

type fakeArtifacts struct {
	request   []byte
	findErr   error
	createErr error
	created   *pipeline.StoredResult
}

func (a *fakeArtifacts) DownloadRequest(_ context.Context, _ string, path string) error {
	return os.WriteFile(path, a.request, 0o600)
}

func (a *fakeArtifacts) FindResult(context.Context, string) (*pipeline.StoredResult, error) {
	if a.findErr != nil {
		return nil, a.findErr
	}
	return nil, nil
}

func (a *fakeArtifacts) CreateResult(context.Context, string, string, pipeline.StoredResult) (*pipeline.StoredResult, error) {
	if a.createErr != nil {
		return nil, a.createErr
	}
	return a.created, nil
}

type fakeEngine struct {
	runErr error
	// blocks makes Run wait for its context, the way a packer withdrawn
	// mid-job does. It is what makes the lost-lock test deterministic: the
	// renewal ticker always fires while the packer is still running.
	blocks bool
}

func (e *fakeEngine) Provenance(context.Context) (pipeline.EngineProvenance, error) {
	return pipeline.EngineProvenance{Version: testEngineVersion, Checksum: testEngineChecksum}, nil
}

func (e *fakeEngine) Run(ctx context.Context, request pipeline.RunRequest) (pipeline.EngineResult, error) {
	if e.blocks {
		<-ctx.Done()
		return pipeline.EngineResult{}, fmt.Errorf("engine: packing canceled: %w", ctx.Err())
	}
	if e.runErr != nil {
		return pipeline.EngineResult{}, e.runErr
	}
	if err := os.WriteFile(request.OutputPath, []byte("packed"), 0o600); err != nil {
		return pipeline.EngineResult{}, err
	}
	return storedResult().Result, nil
}

func storedResult() pipeline.StoredResult {
	return pipeline.StoredResult{
		Engine: pipeline.EngineProvenance{Version: testEngineVersion, Checksum: testEngineChecksum},
		Result: pipeline.EngineResult{
			FileName:    "output",
			ContentType: "application/octet-stream",
			SizeBytes:   6,
			Checksum:    testResultChecksum,
		},
	}
}

func logLines(t *testing.T, out *bytes.Buffer) []logLine {
	t.Helper()
	var lines []logLine
	for _, raw := range strings.Split(out.String(), "\n") {
		if raw == "" {
			continue
		}
		var line logLine
		if err := json.Unmarshal([]byte(raw), &line); err != nil {
			t.Fatalf("log line %q is not JSON: %v", raw, err)
		}
		if line.Level != "info" && line.Level != "error" {
			t.Fatalf("log line %q has no usable level", raw)
		}
		if line.Message == "" {
			t.Fatalf("log line %q has no message", raw)
		}
		lines = append(lines, line)
	}
	return lines
}

func logMessages(t *testing.T, out *bytes.Buffer) []string {
	t.Helper()
	lines := logLines(t, out)
	messages := make([]string, 0, len(lines))
	for _, line := range lines {
		messages = append(messages, line.Message)
	}
	return messages
}

func TestRunRejectsCommandLineArgumentsWithoutReadingTheEnvironment(t *testing.T) {
	// run, not runWith: this is the only exit run itself can reach without a
	// broker, and it has to be reachable before anything is constructed.
	var out bytes.Buffer

	code := run(context.Background(), []string{"--connection-string=" + testSecretMarker}, &out)

	if code != 2 {
		t.Fatalf("exit code = %d, want 2", code)
	}
	if strings.Contains(out.String(), testSecretMarker) {
		t.Fatalf("the rejected argument was echoed into the log: %s", out.String())
	}
	lines := logLines(t, &out)
	if len(lines) != 1 || lines[0].Level != "error" {
		t.Fatalf("log = %+v, want one error line", lines)
	}
	if lines[0].JobID != "" {
		t.Fatalf("jobId = %q, want empty before any dispatch was decoded", lines[0].JobID)
	}
}

func TestRunWithMapsRunOnceOutcomesToExitCodes(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want int
	}{
		{name: "settled delivery", err: nil, want: 0},
		{name: "empty receive window", err: pipeline.ErrNoMessage, want: 0},
		{
			name: "empty receive window reported through a wrapper",
			err:  fmt.Errorf("azureio: receive dispatch: %w", pipeline.ErrNoMessage),
			want: 0,
		},
		{
			name: "lost lock",
			err:  fmt.Errorf("%w: %w", pipeline.ErrLockLost, errors.New("renew: link detached")),
			want: 1,
		},
		{
			name: "infrastructure failure",
			err:  errors.New("pipeline: upload result for job: 503"),
			want: 1,
		},
		{
			name: "shutdown during receive",
			err:  fmt.Errorf("azureio: receive dispatch: %w", context.Canceled),
			want: 1,
		},
	}

	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			useTestEnvironment(t, t.TempDir(), "1h")
			var out bytes.Buffer
			spy := &connectorSpy{}
			handler := &fakeHandler{err: testCase.err}

			code := runWith(context.Background(), nil, &out, spy.serving(handler, nil))

			if code != testCase.want {
				t.Fatalf("exit code = %d, want %d", code, testCase.want)
			}
			if handler.calls != 1 {
				t.Fatalf("RunOnce called %d times, want exactly 1: this worker takes one message and exits", handler.calls)
			}
			if spy.closes != 1 {
				t.Fatalf("close called %d times, want 1", spy.closes)
			}
			if len(logLines(t, &out)) == 0 {
				t.Fatal("the outcome was not logged")
			}
		})
	}
}

func TestRunWithReportsAConfigurationFailureAsExitTwo(t *testing.T) {
	useTestEnvironment(t, t.TempDir(), "1h")
	// Both service bus authentication modes at once: config.Load rejects it.
	t.Setenv("SERVICE_BUS_NAMESPACE", "packing-test")
	var out bytes.Buffer
	spy := &connectorSpy{}

	code := runWith(context.Background(), nil, &out, spy.serving(&fakeHandler{}, nil))

	if code != 2 {
		t.Fatalf("exit code = %d, want 2", code)
	}
	if spy.calls != 0 {
		t.Fatal("the worker was built from a configuration that failed to load")
	}
	if strings.Contains(out.String(), testSecretMarker) {
		t.Fatalf("a connection string reached the log: %s", out.String())
	}
	lines := logLines(t, &out)
	if len(lines) != 1 || lines[0].Level != "error" || lines[0].JobID != "" {
		t.Fatalf("log = %+v, want one error line with no jobId", lines)
	}
}

func TestRunWithReportsAWorkerThatCannotBeBuiltAsExitTwo(t *testing.T) {
	useTestEnvironment(t, t.TempDir(), "1h")
	var out bytes.Buffer
	spy := &connectorSpy{}

	code := runWith(context.Background(), nil, &out, spy.failing(errors.New("azureio: build blob client")))

	if code != 2 {
		t.Fatalf("exit code = %d, want 2", code)
	}
	if spy.closes != 0 {
		t.Fatal("a construction that failed was closed anyway")
	}
}

func TestRunWithPassesTheLoadedConfigurationToTheConnector(t *testing.T) {
	workRoot := t.TempDir()
	useTestEnvironment(t, workRoot, "250ms")
	var out bytes.Buffer
	spy := &connectorSpy{}

	if code := runWith(context.Background(), nil, &out, spy.serving(&fakeHandler{}, nil)); code != 0 {
		t.Fatalf("exit code = %d, want 0", code)
	}

	if spy.calls != 1 {
		t.Fatalf("connector called %d times, want 1", spy.calls)
	}
	got := spy.cfg
	if got.DispatchQueue != "test-dispatch" || got.ResultQueue != "test-results" {
		t.Fatalf("queues = %q/%q, want test-dispatch/test-results", got.DispatchQueue, got.ResultQueue)
	}
	if got.StorageContainer != "test-models" {
		t.Fatalf("container = %q, want test-models", got.StorageContainer)
	}
	if got.PackerPath != "/test/bin/packer" {
		t.Fatalf("packer path = %q, want /test/bin/packer", got.PackerPath)
	}
	if got.WorkRoot != workRoot {
		t.Fatalf("work root = %q, want %q", got.WorkRoot, workRoot)
	}
	if got.ReceiveTimeout != 5*time.Second {
		t.Fatalf("receive timeout = %s, want 5s", got.ReceiveTimeout)
	}
	if got.LockRenewInterval != 250*time.Millisecond {
		t.Fatalf("lock renew interval = %s, want 250ms", got.LockRenewInterval)
	}
	if got.ServiceBusConnectionString != testConnectionString {
		t.Fatal("the service bus connection string did not reach the connector")
	}
}

func TestCloseFailureDoesNotChangeTheExitCode(t *testing.T) {
	useTestEnvironment(t, t.TempDir(), "1h")
	var out bytes.Buffer
	spy := &connectorSpy{}

	code := runWith(context.Background(), nil, &out, spy.serving(&fakeHandler{}, errors.New("amqp: detach timed out")))

	if code != 0 {
		t.Fatalf("exit code = %d, want 0: hanging up badly cannot undo a settled delivery", code)
	}
	if spy.closes != 1 {
		t.Fatalf("close called %d times, want 1", spy.closes)
	}
	messages := logMessages(t, &out)
	if !strings.Contains(strings.Join(messages, "\n"), "amqp: detach timed out") {
		t.Fatalf("the close failure was swallowed: %v", messages)
	}
}

func TestRunWithReportsAPackedJobAsExitZero(t *testing.T) {
	useTestEnvironment(t, t.TempDir(), "1h")
	var out bytes.Buffer
	queue := &fakeQueue{delivery: &fakeDelivery{body: dispatchBody()}}
	artifacts := &fakeArtifacts{request: requestBody(), created: pointerTo(storedResult())}

	code := runWith(context.Background(), nil, &out, processing(queue, artifacts, &fakeEngine{}))

	if code != 0 {
		t.Fatalf("exit code = %d, want 0", code)
	}
	if got := queue.eventTypes(); !slices.Equal(got, []string{"started", "succeeded"}) {
		t.Fatalf("events = %v, want [started succeeded]", got)
	}
	if got := queue.settled(); !slices.Equal(got, []string{"complete"}) {
		t.Fatalf("settlement = %v, want [complete]", got)
	}
	want := []string{
		"dispatch received",
		"started event sent",
		"succeeded event sent",
		"dispatch completed",
		"dispatch handled",
	}
	if got := logMessages(t, &out); !slices.Equal(got, want) {
		t.Fatalf("log messages = %v, want %v", got, want)
	}
}

func TestRunWithReportsAnEmptyReceiveWindowAsExitZero(t *testing.T) {
	useTestEnvironment(t, t.TempDir(), "1h")
	var out bytes.Buffer
	queue := &fakeQueue{receiveErr: pipeline.ErrNoMessage}

	code := runWith(context.Background(), nil, &out, processing(queue, &fakeArtifacts{}, &fakeEngine{}))

	if code != 0 {
		t.Fatalf("exit code = %d, want 0: an idle queue is a normal outcome for a one-shot worker", code)
	}
	if got := queue.settled(); len(got) != 0 {
		t.Fatalf("settlement = %v, want none", got)
	}
	lines := logLines(t, &out)
	if len(lines) != 1 || lines[0].JobID != "" {
		t.Fatalf("log = %+v, want one line with no jobId: nothing was received, so nothing names a job", lines)
	}
}

// The load-bearing test of this file. A packer that rejects a job is a
// successful execution of the worker: the delivery was settled, the failure
// was reported, and a Container Apps Job must not retry it.
func TestRunWithReportsAnEngineFailureAsExitZero(t *testing.T) {
	useTestEnvironment(t, t.TempDir(), "1h")
	var out bytes.Buffer
	queue := &fakeQueue{delivery: &fakeDelivery{body: dispatchBody()}}
	artifacts := &fakeArtifacts{request: requestBody()}
	engine := &fakeEngine{runErr: &pipeline.EngineFailure{Reason: "packer exited with code 3"}}

	code := runWith(context.Background(), nil, &out, processing(queue, artifacts, engine))

	if code != 0 {
		t.Fatalf("exit code = %d, want 0: the job failed, the worker did not", code)
	}
	if got := queue.eventTypes(); !slices.Equal(got, []string{"started", "failed"}) {
		t.Fatalf("events = %v, want [started failed]", got)
	}
	if got := queue.settled(); !slices.Equal(got, []string{"complete"}) {
		t.Fatalf("settlement = %v, want [complete]", got)
	}
	if got := logMessages(t, &out); !slices.Contains(got, "failed event sent") {
		t.Fatalf("log messages = %v, want a line naming the failed event", got)
	}
}

func TestRunWithReportsAnInfrastructureFailureAsExitOne(t *testing.T) {
	useTestEnvironment(t, t.TempDir(), "1h")
	var out bytes.Buffer
	queue := &fakeQueue{delivery: &fakeDelivery{body: dispatchBody()}}
	artifacts := &fakeArtifacts{request: requestBody(), findErr: errors.New("storage: 503")}

	code := runWith(context.Background(), nil, &out, processing(queue, artifacts, &fakeEngine{}))

	if code != 1 {
		t.Fatalf("exit code = %d, want 1", code)
	}
	if got := queue.settled(); !slices.Equal(got, []string{"abandon"}) {
		t.Fatalf("settlement = %v, want [abandon]", got)
	}
	if got := queue.eventTypes(); len(got) != 0 {
		t.Fatalf("events = %v, want none", got)
	}
	if got := logMessages(t, &out); !startsWith(got, "dispatch failed") {
		t.Fatalf("log messages = %v, want the failure named as such", got)
	}
}

func TestRunWithLeavesALostLockUnsettledAndExitsOne(t *testing.T) {
	useTestEnvironment(t, t.TempDir(), "10ms")
	var out bytes.Buffer
	queue := &fakeQueue{
		delivery: &fakeDelivery{body: dispatchBody()},
		renewErr: errors.New("servicebus: message lock expired"),
	}
	artifacts := &fakeArtifacts{request: requestBody()}

	code := runWith(context.Background(), nil, &out, processing(queue, artifacts, &fakeEngine{blocks: true}))

	if code != 1 {
		t.Fatalf("exit code = %d, want 1", code)
	}
	if got := queue.settled(); len(got) != 0 {
		t.Fatalf("settlement = %v, want none: another delivery owns the job once the lock is gone", got)
	}
	// The exit code alone cannot distinguish this from any other
	// infrastructure failure — both are 1 — so the log line is the only thing
	// that tells an operator the delivery was left deliberately unsettled.
	if got := logMessages(t, &out); !startsWith(got, "dispatch lock lost") {
		t.Fatalf("log messages = %v, want the lost lock named as such", got)
	}
}

func TestLogCarriesTheJobIDOnceTheDispatchIsDecoded(t *testing.T) {
	useTestEnvironment(t, t.TempDir(), "1h")
	var out bytes.Buffer
	queue := &fakeQueue{delivery: &fakeDelivery{body: dispatchBody()}}
	artifacts := &fakeArtifacts{request: requestBody(), created: pointerTo(storedResult())}

	if code := runWith(context.Background(), nil, &out, processing(queue, artifacts, &fakeEngine{})); code != 0 {
		t.Fatalf("exit code = %d, want 0", code)
	}

	lines := logLines(t, &out)
	if len(lines) == 0 {
		t.Fatal("nothing was logged")
	}
	for _, line := range lines {
		if line.JobID != testJobID {
			t.Fatalf("line %q carries jobId %q, want %q", line.Message, line.JobID, testJobID)
		}
	}
}

func TestLogNeverCarriesTheSpecOrACredential(t *testing.T) {
	useTestEnvironment(t, t.TempDir(), "1h")
	var out bytes.Buffer
	queue := &fakeQueue{delivery: &fakeDelivery{body: dispatchBody()}}
	artifacts := &fakeArtifacts{
		request:   requestBody(),
		createErr: errors.New("storage: conditional create rejected"),
	}

	if code := runWith(context.Background(), nil, &out, processing(queue, artifacts, &fakeEngine{})); code != 1 {
		t.Fatalf("exit code = %d, want 1", code)
	}

	logged := out.String()
	for _, secret := range []string{testSpecMarker, testSecretMarker} {
		if strings.Contains(logged, secret) {
			t.Fatalf("%q reached the log: %s", secret, logged)
		}
	}
}

// Cancellation is driven through the context rather than through a real
// signal: syscall.SIGTERM compiles on Windows but is never delivered there, so
// a signal-driven test would be a test that cannot fail on the machine this is
// developed on. main's signal.NotifyContext is what turns the signal into this
// cancellation on Linux.
func TestRunWithPropagatesCallerCancellation(t *testing.T) {
	useTestEnvironment(t, t.TempDir(), "1h")
	ctx, shutDown := context.WithCancel(context.Background())
	shutDown()
	var out bytes.Buffer
	spy := &connectorSpy{}
	handler := &fakeHandler{err: fmt.Errorf("azureio: receive dispatch: %w", context.Canceled)}

	code := runWith(ctx, nil, &out, spy.serving(handler, nil))

	if code != 1 {
		t.Fatalf("exit code = %d, want 1", code)
	}
	if handler.ctxErr == nil {
		t.Fatal("RunOnce was handed a context that had not been canceled")
	}
	if spy.closeCtxErr != nil {
		t.Fatalf("close context error = %v, want none: the links still have to be closed after a shutdown", spy.closeCtxErr)
	}
}

func TestConnectAzureRejectsAMalformedServiceBusConnectionString(t *testing.T) {
	handler, closeWorker, err := connectAzure(config.Config{
		ServiceBusConnectionString: "not-a-connection-string",
		StorageConnectionString:    testConnectionString,
	}, newLogger(&bytes.Buffer{}))

	if err == nil {
		t.Fatal("a malformed connection string built a worker")
	}
	if handler != nil || closeWorker != nil {
		t.Fatal("a failed construction returned something to run or close")
	}
	if strings.Contains(err.Error(), testSecretMarker) {
		t.Fatalf("a connection string reached an error message: %v", err)
	}
}

func pointerTo[T any](value T) *T { return &value }

func startsWith(messages []string, prefix string) bool {
	return slices.ContainsFunc(messages, func(message string) bool {
		return strings.HasPrefix(message, prefix)
	})
}
