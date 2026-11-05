package pipeline

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/converter"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/testsuite"

	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
)

const (
	testJobID      = "00000000-0000-0000-0000-000000000001"
	testStorageKey = "files/0b6c3c1e-4c4f-4a39-9b5e-2b8c2e6b1f00"
	testFileName   = "bunny.obj"
	testInput      = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"
	testEngineVer  = "v0.0.2"
	testEngineSum  = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	testMaxRuntime = 120
)

func inputChecksum() string {
	sum := sha256.Sum256([]byte(testInput))
	return hex.EncodeToString(sum[:])
}

func requestBodyWith(format, parameters string) []byte {
	return []byte(`{"requestVersion":3,"maxRuntimeSeconds":120,"inputSchemaVersion":1,"items":[{"storageKey":"` +
		testStorageKey + `","fileName":"` + testFileName + `","format":"` + format + `","checksum":"` +
		inputChecksum() + `","copies":2}],"parameters":` + parameters + `}`)
}

func requestBody() []byte {
	return requestBodyWith("OBJ", "{}")
}

func input() contracts.RunEngineInput {
	return contracts.RunEngineInput{ContractVersion: contracts.ActivityVersion, JobID: testJobID}
}

type recorder struct {
	mu    sync.Mutex
	calls []string
}

func (r *recorder) record(call string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.calls = append(r.calls, call)
}

func (r *recorder) snapshot() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return slices.Clone(r.calls)
}

type signal struct {
	workflowID string
	runID      string
	name       string
	arg        any
}

type fakeSignaler struct {
	rec *recorder
	err error

	mu      sync.Mutex
	signals []signal
}

func (s *fakeSignaler) SignalWorkflow(_ context.Context, workflowID, runID, signalName string, arg any) error {
	s.rec.record("signal:" + signalName)
	s.mu.Lock()
	s.signals = append(s.signals, signal{workflowID: workflowID, runID: runID, name: signalName, arg: arg})
	s.mu.Unlock()
	return s.err
}

func (s *fakeSignaler) sent() []signal {
	s.mu.Lock()
	defer s.mu.Unlock()
	return slices.Clone(s.signals)
}

type fakeArtifacts struct {
	rec *recorder

	find        *StoredResult
	findErr     error
	request     []byte
	downloadErr error
	inputs      map[string][]byte
	inputErr    error
	created     *StoredResult
	createErr   error

	mu            sync.Mutex
	findJobID     string
	downloadJobID string
	inputKeys     []string
	createJobID   string
	createPath    string
	createOffered StoredResult
}

func (a *fakeArtifacts) FindResult(_ context.Context, jobID string) (*StoredResult, error) {
	a.rec.record("findResult")
	a.mu.Lock()
	a.findJobID = jobID
	a.mu.Unlock()
	if a.findErr != nil {
		return nil, a.findErr
	}
	return a.find, nil
}

func (a *fakeArtifacts) DownloadRequest(_ context.Context, jobID, path string) error {
	a.rec.record("download")
	a.mu.Lock()
	a.downloadJobID = jobID
	a.mu.Unlock()
	if a.downloadErr != nil {
		return a.downloadErr
	}
	return os.WriteFile(path, a.request, 0o600)
}

func (a *fakeArtifacts) DownloadInput(_ context.Context, storageKey, path string) error {
	a.rec.record("downloadInput")
	a.mu.Lock()
	a.inputKeys = append(a.inputKeys, storageKey)
	a.mu.Unlock()
	if a.inputErr != nil {
		return a.inputErr
	}
	body, ok := a.inputs[storageKey]
	if !ok {
		return fmt.Errorf("fake: %s: %w", storageKey, ErrInputNotFound)
	}
	return os.WriteFile(path, body, 0o600)
}

func (a *fakeArtifacts) CreateResult(_ context.Context, jobID, path string, offered StoredResult) (*StoredResult, error) {
	a.rec.record("createResult")
	a.mu.Lock()
	a.createJobID = jobID
	a.createPath = path
	a.createOffered = offered
	a.mu.Unlock()
	if a.createErr != nil {
		return nil, a.createErr
	}
	return a.created, nil
}

type fakeEngine struct {
	rec *recorder

	provenance    EngineProvenance
	provenanceErr error
	provenanceFn  func(context.Context) (EngineProvenance, error)
	run           func(context.Context, RunRequest) (EngineResult, error)

	mu          sync.Mutex
	lastRequest RunRequest
	inputsSeen  []string
}

func (e *fakeEngine) Provenance(ctx context.Context) (EngineProvenance, error) {
	e.rec.record("provenance")
	if e.provenanceFn != nil {
		return e.provenanceFn(ctx)
	}
	if e.provenanceErr != nil {
		return EngineProvenance{}, e.provenanceErr
	}
	return e.provenance, nil
}

func (e *fakeEngine) Run(ctx context.Context, request RunRequest) (EngineResult, error) {
	e.rec.record("run")
	seen := make([]string, 0, len(request.Inputs))
	for _, in := range request.Inputs {
		content, _ := os.ReadFile(in.Path)
		seen = append(seen, string(content))
	}
	e.mu.Lock()
	e.lastRequest = request
	e.inputsSeen = seen
	e.mu.Unlock()
	return e.run(ctx, request)
}

var (
	engineResult = EngineResult{
		FileName:    "packed.obj",
		ContentType: "model/obj",
		SizeBytes:   13,
		Checksum:    strings.Repeat("c", 64),
	}
	createdResult = StoredResult{
		Engine: EngineProvenance{Version: "v0.0.3", Checksum: strings.Repeat("b", 64)},
		Result: EngineResult{
			FileName:    "packed.obj",
			ContentType: "model/obj",
			SizeBytes:   99,
			Checksum:    strings.Repeat("d", 64),
		},
	}
	storedResult = StoredResult{
		Engine: EngineProvenance{Version: "v0.0.1", Checksum: strings.Repeat("e", 64)},
		Result: EngineResult{
			FileName:    "packed.obj",
			ContentType: "model/obj",
			SizeBytes:   42,
			Checksum:    strings.Repeat("f", 64),
		},
	}
)

func writeOutput(_ context.Context, request RunRequest) (EngineResult, error) {
	if err := os.WriteFile(request.OutputPath, []byte("packed bytes\n"), 0o600); err != nil {
		return EngineResult{}, err
	}
	return engineResult, nil
}

type harness struct {
	rec       *recorder
	artifacts *fakeArtifacts
	engine    *fakeEngine
	signaler  *fakeSignaler
	workRoot  string
	interval  time.Duration
	env       *testsuite.TestActivityEnvironment

	heartbeats atomic.Int32
	execution  atomic.Value
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	rec := &recorder{}
	var suite testsuite.WorkflowTestSuite
	h := &harness{
		rec: rec,
		artifacts: &fakeArtifacts{
			rec:     rec,
			request: requestBody(),
			inputs:  map[string][]byte{testStorageKey: []byte(testInput)},
			created: cloneResult(createdResult),
		},
		engine: &fakeEngine{
			rec:        rec,
			provenance: EngineProvenance{Version: testEngineVer, Checksum: testEngineSum},
			run:        writeOutput,
		},
		signaler: &fakeSignaler{rec: rec},
		workRoot: t.TempDir(),
		interval: time.Hour,
		env:      suite.NewTestActivityEnvironment(),
	}
	h.env.SetOnActivityHeartbeatListener(func(*activity.Info, converter.EncodedValues) {
		h.heartbeats.Add(1)
	})
	return h
}

func cloneResult(result StoredResult) *StoredResult {
	copied := result
	return &copied
}

func (h *harness) run(in contracts.RunEngineInput) (contracts.EngineResult, error) {
	act := NewActivity(h.artifacts, h.engine, h.signaler, h.workRoot, h.interval)
	h.env.RegisterActivityWithOptions(act.RunEngine, activity.RegisterOptions{Name: contracts.RunEngineActivity})
	value, err := h.env.ExecuteActivity(contracts.RunEngineActivity, in)
	if err != nil {
		return contracts.EngineResult{}, err
	}
	var result contracts.EngineResult
	if err := value.Get(&result); err != nil {
		return contracts.EngineResult{}, err
	}
	return result, nil
}

func assertCalls(t *testing.T, got, want []string) {
	t.Helper()
	if !slices.Equal(got, want) {
		t.Fatalf("call sequence mismatch:\n got:  %v\n want: %v", got, want)
	}
}

func assertNoCalls(t *testing.T, got []string, unwanted ...string) {
	t.Helper()
	for _, call := range unwanted {
		if slices.Contains(got, call) {
			t.Fatalf("unexpected call %q in sequence %v", call, got)
		}
	}
}

func assertWorkspaceRemoved(t *testing.T, workRoot string) {
	t.Helper()
	entries, err := os.ReadDir(workRoot)
	if err != nil {
		t.Fatalf("work root %s is gone, it must never be deleted: %v", workRoot, err)
	}
	if len(entries) != 0 {
		t.Fatalf("work root %s still holds %d entries, workspace leaked", workRoot, len(entries))
	}
}

func applicationError(t *testing.T, err error) *temporal.ApplicationError {
	t.Helper()
	var appErr *temporal.ApplicationError
	if !errors.As(err, &appErr) {
		t.Fatalf("expected an application error, got %T: %v", err, err)
	}
	return appErr
}

func assertRetryable(t *testing.T, err error) {
	t.Helper()
	if err == nil {
		t.Fatal("expected an error")
	}
	var appErr *temporal.ApplicationError
	if errors.As(err, &appErr) && appErr.NonRetryable() {
		t.Fatalf("expected a retryable error, got non-retryable %s: %v", appErr.Type(), err)
	}
}

func assertEngineFailure(t *testing.T, err error, reason string) {
	t.Helper()
	appErr := applicationError(t, err)
	if appErr.Type() != contracts.EngineFailureType || !appErr.NonRetryable() {
		t.Fatalf("error type %s nonRetryable=%t, want non-retryable %s", appErr.Type(), appErr.NonRetryable(), contracts.EngineFailureType)
	}
	var details contracts.EngineFailureDetails
	if err := appErr.Details(&details); err != nil {
		t.Fatalf("details: %v", err)
	}
	want := contracts.EngineFailureDetails{ContractVersion: contracts.ActivityVersion, EngineVersion: testEngineVer, EngineChecksum: testEngineSum, Reason: reason}
	if details != want {
		t.Fatalf("details = %+v, want %+v", details, want)
	}
}

func TestRunEngineSuccessCallsPortsInOrderAndReturnsTheStoredWinner(t *testing.T) {
	h := newHarness(t)

	result, err := h.run(input())
	if err != nil {
		t.Fatalf("RunEngine: %v", err)
	}

	assertCalls(t, h.rec.snapshot(), []string{
		"findResult", "download", "provenance", "downloadInput", "signal:engineStarted", "run", "createResult",
	})
	assertWorkspaceRemoved(t, h.workRoot)
	want := contracts.EngineResult{
		ContractVersion:   contracts.ActivityVersion,
		EngineVersion:     createdResult.Engine.Version,
		EngineChecksum:    createdResult.Engine.Checksum,
		ResultFileName:    createdResult.Result.FileName,
		ResultContentType: createdResult.Result.ContentType,
		ResultSizeBytes:   createdResult.Result.SizeBytes,
		ResultChecksum:    createdResult.Result.Checksum,
	}
	if result != want {
		t.Fatalf("result = %+v, want the stored winner %+v", result, want)
	}
}

func TestRunEngineSignalsStartedToItsOwnWorkflowWithTheEngineProvenance(t *testing.T) {
	h := newHarness(t)
	h.engine.provenanceFn = func(ctx context.Context) (EngineProvenance, error) {
		info := activity.GetInfo(ctx)
		h.execution.Store([2]string{info.WorkflowExecution.ID, info.WorkflowExecution.RunID})
		return EngineProvenance{Version: testEngineVer, Checksum: testEngineSum}, nil
	}

	if _, err := h.run(input()); err != nil {
		t.Fatalf("RunEngine: %v", err)
	}

	signals := h.signaler.sent()
	if len(signals) != 1 {
		t.Fatalf("expected one signal, got %d", len(signals))
	}
	own := h.execution.Load().([2]string)
	if signals[0].workflowID != own[0] || signals[0].runID != own[1] {
		t.Fatalf("signalled %s/%s, want the activity's own workflow %s/%s", signals[0].workflowID, signals[0].runID, own[0], own[1])
	}
	want := contracts.EngineStarted{ContractVersion: contracts.ActivityVersion, EngineVersion: testEngineVer, EngineChecksum: testEngineSum}
	if signals[0].name != contracts.EngineStartedSignal || signals[0].arg != want {
		t.Fatalf("signal = %s %+v, want %s %+v", signals[0].name, signals[0].arg, contracts.EngineStartedSignal, want)
	}
}

func TestRunEnginePassesTheLowercasedJobIdToEveryPort(t *testing.T) {
	h := newHarness(t)

	if _, err := h.run(contracts.RunEngineInput{ContractVersion: contracts.ActivityVersion, JobID: strings.ToUpper(testJobID)}); err != nil {
		t.Fatalf("RunEngine: %v", err)
	}

	h.artifacts.mu.Lock()
	defer h.artifacts.mu.Unlock()
	for name, got := range map[string]string{
		"FindResult":      h.artifacts.findJobID,
		"DownloadRequest": h.artifacts.downloadJobID,
		"CreateResult":    h.artifacts.createJobID,
	} {
		if got != testJobID {
			t.Fatalf("%s received job id %q, want %q", name, got, testJobID)
		}
	}
}

func TestRunEngineHandsThePackerTheVerifiedInputsUnderIndexNames(t *testing.T) {
	h := newHarness(t)

	if _, err := h.run(input()); err != nil {
		t.Fatalf("RunEngine: %v", err)
	}

	h.engine.mu.Lock()
	defer h.engine.mu.Unlock()
	inputs := h.engine.lastRequest.Inputs
	if len(inputs) != 1 {
		t.Fatalf("packer received %d inputs, want 1", len(inputs))
	}
	if filepath.Base(inputs[0].Path) != "0.obj" || filepath.Base(filepath.Dir(inputs[0].Path)) != "inputs" {
		t.Fatalf("input path %s is not inputs/0.obj", inputs[0].Path)
	}
	if inputs[0].DisplayName != testFileName || inputs[0].Copies != 2 {
		t.Fatalf("input = %+v, want %s with 2 copies", inputs[0], testFileName)
	}
	if h.engine.inputsSeen[0] != testInput {
		t.Fatalf("packer read %q, want the downloaded bytes", h.engine.inputsSeen[0])
	}
	h.artifacts.mu.Lock()
	defer h.artifacts.mu.Unlock()
	if !slices.Equal(h.artifacts.inputKeys, []string{testStorageKey}) {
		t.Fatalf("downloaded %v, want only %s", h.artifacts.inputKeys, testStorageKey)
	}
}

func TestRunEngineBuildsRunRequestFromEnvelope(t *testing.T) {
	h := newHarness(t)

	if _, err := h.run(input()); err != nil {
		t.Fatalf("RunEngine: %v", err)
	}

	h.engine.mu.Lock()
	request := h.engine.lastRequest
	h.engine.mu.Unlock()
	if request.Runtime != testMaxRuntime*time.Second {
		t.Fatalf("run runtime = %s, want %ds from the envelope", request.Runtime, testMaxRuntime)
	}
	if len(request.ParameterArgs) != 0 {
		t.Fatalf("parameter args = %q, want none", request.ParameterArgs)
	}
	workspace := filepath.Dir(request.OutputPath)
	if filepath.Base(request.OutputPath) != "packed.obj" {
		t.Fatalf("output %s is not packed.obj", request.OutputPath)
	}
	if filepath.Dir(workspace) != h.workRoot || !strings.HasPrefix(filepath.Base(workspace), "packing-"+testJobID+"-") {
		t.Fatalf("workspace %s is not a job-named directory under %s", workspace, h.workRoot)
	}
	h.artifacts.mu.Lock()
	defer h.artifacts.mu.Unlock()
	if h.artifacts.createPath != request.OutputPath || h.artifacts.createOffered.Result != engineResult {
		t.Fatalf("uploaded %s with %+v, want the packer output %s with %+v", h.artifacts.createPath, h.artifacts.createOffered.Result, request.OutputPath, engineResult)
	}
}

func TestRunEngineReturnsAStoredResultWithoutRunningThePacker(t *testing.T) {
	h := newHarness(t)
	h.artifacts.find = cloneResult(storedResult)

	result, err := h.run(input())
	if err != nil {
		t.Fatalf("RunEngine: %v", err)
	}

	assertCalls(t, h.rec.snapshot(), []string{"findResult", "signal:engineStarted"})
	if result.EngineVersion != storedResult.Engine.Version || result.ResultChecksum != storedResult.Result.Checksum {
		t.Fatalf("result = %+v, want the stored %+v", result, storedResult)
	}
	started := h.signaler.sent()[0].arg.(contracts.EngineStarted)
	if started.EngineVersion != storedResult.Engine.Version || started.EngineChecksum != storedResult.Engine.Checksum {
		t.Fatalf("started carries %+v, want the stored provenance %+v", started, storedResult.Engine)
	}
}

func TestRunEngineRejectsAnUnusableStoredResultForRetry(t *testing.T) {
	h := newHarness(t)
	broken := cloneResult(storedResult)
	broken.Result.Checksum = "not-a-checksum"
	h.artifacts.find = broken

	_, err := h.run(input())

	assertRetryable(t, err)
	assertNoCalls(t, h.rec.snapshot(), "signal:engineStarted", "run")
}

func TestRunEngineReportsAnEngineFailureAsNonRetryableWithItsDetails(t *testing.T) {
	for _, tc := range []struct {
		name   string
		reason string
		want   string
	}{
		{name: "nonzero exit", reason: "packer exited with code 2", want: "packer exited with code 2"},
		{name: "control characters", reason: "line one\nline\ttwo", want: "line one line two"},
		{name: "blank", reason: "  ", want: unreportedFailure},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			h.engine.run = func(context.Context, RunRequest) (EngineResult, error) {
				return EngineResult{}, &EngineFailure{Reason: tc.reason}
			}

			_, err := h.run(input())

			assertEngineFailure(t, err, tc.want)
			assertNoCalls(t, h.rec.snapshot(), "createResult")
			assertWorkspaceRemoved(t, h.workRoot)
		})
	}
}

func TestRunEngineReportsUnusableInputsAsEngineFailures(t *testing.T) {
	for _, tc := range []struct {
		name    string
		arrange func(*harness)
		reason  string
	}{
		{
			name:    "deleted after queueing",
			arrange: func(h *harness) { h.artifacts.inputs = map[string][]byte{} },
			reason:  "Input file 'bunny.obj' is no longer available",
		},
		{
			name:    "bytes changed",
			arrange: func(h *harness) { h.artifacts.inputs[testStorageKey] = []byte("v 9 9 9\n") },
			reason:  "Input file 'bunny.obj' does not match its recorded checksum",
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			tc.arrange(h)

			_, err := h.run(input())

			assertEngineFailure(t, err, tc.reason)
			assertNoCalls(t, h.rec.snapshot(), "signal:engineStarted", "run", "createResult")
			assertWorkspaceRemoved(t, h.workRoot)
		})
	}
}

func TestRunEngineTreatsInfrastructureFailuresAsRetryable(t *testing.T) {
	for _, tc := range []struct {
		name    string
		arrange func(*harness)
		absent  []string
	}{
		{name: "result lookup", arrange: func(h *harness) { h.artifacts.findErr = errors.New("storage down") }, absent: []string{"download", "signal:engineStarted"}},
		{name: "request download", arrange: func(h *harness) { h.artifacts.downloadErr = errors.New("storage down") }, absent: []string{"signal:engineStarted", "run"}},
		{name: "provenance", arrange: func(h *harness) { h.engine.provenanceErr = errors.New("packer missing") }, absent: []string{"downloadInput", "signal:engineStarted", "run"}},
		{name: "unusable provenance", arrange: func(h *harness) { h.engine.provenance.Checksum = "short" }, absent: []string{"downloadInput", "signal:engineStarted", "run"}},
		{name: "input download", arrange: func(h *harness) { h.artifacts.inputErr = errors.New("storage down") }, absent: []string{"signal:engineStarted", "run"}},
		{name: "signal", arrange: func(h *harness) { h.signaler.err = errors.New("temporal down") }, absent: []string{"run"}},
		{name: "upload", arrange: func(h *harness) { h.artifacts.createErr = errors.New("storage down") }},
		{name: "packer infrastructure", arrange: func(h *harness) {
			h.engine.run = func(context.Context, RunRequest) (EngineResult, error) {
				return EngineResult{}, errors.New("exec format error")
			}
		}, absent: []string{"createResult"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			tc.arrange(h)

			_, err := h.run(input())

			assertRetryable(t, err)
			assertNoCalls(t, h.rec.snapshot(), tc.absent...)
			assertWorkspaceRemoved(t, h.workRoot)
		})
	}
}

func TestRunEngineRejectsAnInvalidRequestWithoutRunningThePacker(t *testing.T) {
	for _, tc := range []struct {
		name    string
		request []byte
	}{
		{name: "another request version", request: []byte(`{"requestVersion":1,"maxRuntimeSeconds":60,"spec":{}}`)},
		{name: "a format the engine cannot read", request: requestBodyWith("STL", "{}")},
		{name: "an unknown parameter", request: requestBodyWith("OBJ", `{"seed":7}`)},
		{name: "not JSON", request: []byte(`{not json`)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			h.artifacts.request = tc.request

			_, err := h.run(input())

			appErr := applicationError(t, err)
			if appErr.Type() != contracts.InvalidInputType || !appErr.NonRetryable() {
				t.Fatalf("error type %s nonRetryable=%t, want non-retryable %s", appErr.Type(), appErr.NonRetryable(), contracts.InvalidInputType)
			}
			assertCalls(t, h.rec.snapshot(), []string{"findResult", "download"})
			assertWorkspaceRemoved(t, h.workRoot)
		})
	}
}

func TestRunEngineRejectsAnInvalidInputWithoutTouchingAnyPort(t *testing.T) {
	for _, in := range []contracts.RunEngineInput{
		{ContractVersion: 1, JobID: testJobID},
		{ContractVersion: contracts.ActivityVersion, JobID: "not-a-uuid"},
	} {
		h := newHarness(t)

		_, err := h.run(in)

		appErr := applicationError(t, err)
		if appErr.Type() != contracts.InvalidInputType || !appErr.NonRetryable() {
			t.Fatalf("input %+v: error type %s nonRetryable=%t, want non-retryable %s", in, appErr.Type(), appErr.NonRetryable(), contracts.InvalidInputType)
		}
		assertCalls(t, h.rec.snapshot(), nil)
	}
}

func TestRunEngineHeartbeatsWhileThePackerRuns(t *testing.T) {
	h := newHarness(t)
	h.interval = 10 * time.Millisecond
	h.engine.run = func(ctx context.Context, request RunRequest) (EngineResult, error) {
		select {
		case <-time.After(200 * time.Millisecond):
		case <-ctx.Done():
			return EngineResult{}, ctx.Err()
		}
		return writeOutput(ctx, request)
	}

	if _, err := h.run(input()); err != nil {
		t.Fatalf("RunEngine: %v", err)
	}

	if got := h.heartbeats.Load(); got < 1 {
		t.Fatalf("recorded %d heartbeats during a 200ms run at a 10ms interval, want at least one", got)
	}
}

func TestRunEngineRefusesANonPositiveHeartbeatInterval(t *testing.T) {
	h := newHarness(t)
	h.interval = 0

	_, err := h.run(input())

	assertRetryable(t, err)
	assertCalls(t, h.rec.snapshot(), nil)
}
