package pipeline

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
)

const (
	testJobID = "00000000-0000-0000-0000-000000000001"
	// testSpec carries < and & on purpose: json.Marshal would escape them to
	// < and &, so a spec that survives to input.json byte for byte
	// proves the envelope was never re-encoded on its way to disk.
	testSpec       = `{"label":"crate <a> & <b>","boxes":[{"w":1}]}`
	testEngineVer  = "packer 0.3.0"
	testEngineSum  = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	testMaxRuntime = 60
)

func dispatchBody() []byte {
	return []byte(`{"messageVersion":1,"jobId":"` + testJobID + `"}`)
}

func requestBody() []byte {
	return []byte(fmt.Sprintf(`{"requestVersion":1,"maxRuntimeSeconds":%d,"spec":%s}`, testMaxRuntime, testSpec))
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

type fakeDelivery struct {
	body []byte
}

func (d fakeDelivery) Body() []byte { return d.body }

type fakeQueue struct {
	rec *recorder

	delivery   Delivery
	receiveErr error

	// renewErr is returned by the FIRST RenewLock call only; the renewal
	// goroutine stops at the first error, so one failure is all a test can
	// observe.
	renewErr error
	// renewGate, when set, holds RenewLock until the test opens it. It is
	// what makes "the lock is lost while the packer is running" a fact
	// rather than a race with a ticker.
	renewGate <-chan struct{}

	sendErrs    map[string]error
	completeErr error
	abandonErr  error

	mu                sync.Mutex
	events            []contracts.WorkerEvent
	renewals          int
	closed            bool
	onRenewAfterClose func()
}

func (q *fakeQueue) ReceiveOne(context.Context) (Delivery, error) {
	q.rec.record("receive")
	if q.receiveErr != nil {
		return nil, q.receiveErr
	}
	return q.delivery, nil
}

func (q *fakeQueue) RenewLock(ctx context.Context, _ Delivery) error {
	if q.renewGate != nil {
		select {
		case <-q.renewGate:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	q.rec.record("renewLock")

	q.mu.Lock()
	defer q.mu.Unlock()
	q.renewals++
	if q.closed && q.onRenewAfterClose != nil {
		q.onRenewAfterClose()
	}
	if q.renewals == 1 {
		return q.renewErr
	}
	return nil
}

func (q *fakeQueue) SendEvent(_ context.Context, event contracts.WorkerEvent) error {
	q.rec.record("send:" + event.EventType)
	q.mu.Lock()
	q.events = append(q.events, event)
	q.mu.Unlock()
	return q.sendErrs[event.EventType]
}

func (q *fakeQueue) Complete(context.Context, Delivery) error {
	q.rec.record("complete")
	return q.completeErr
}

func (q *fakeQueue) Abandon(context.Context, Delivery) error {
	q.rec.record("abandon")
	return q.abandonErr
}

func (q *fakeQueue) sentEvents() []contracts.WorkerEvent {
	q.mu.Lock()
	defer q.mu.Unlock()
	return slices.Clone(q.events)
}

func (q *fakeQueue) close() {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.closed = true
}

type fakeArtifacts struct {
	rec *recorder

	find        *StoredResult
	findErr     error
	request     []byte
	downloadErr error
	created     *StoredResult
	createErr   error

	mu            sync.Mutex
	findJobID     string
	downloadJobID string
	downloadPath  string
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
	a.downloadPath = path
	a.mu.Unlock()
	if a.downloadErr != nil {
		return a.downloadErr
	}
	return os.WriteFile(path, a.request, 0o600)
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
	run           func(context.Context, RunRequest) (EngineResult, error)

	mu          sync.Mutex
	lastRequest RunRequest
	specSeen    []byte
	specMode    fs.FileMode
}

func (e *fakeEngine) Provenance(ctx context.Context) (EngineProvenance, error) {
	e.rec.record("provenance")
	if err := ctx.Err(); err != nil {
		return EngineProvenance{}, err
	}
	if e.provenanceErr != nil {
		return EngineProvenance{}, e.provenanceErr
	}
	return e.provenance, nil
}

func (e *fakeEngine) Run(ctx context.Context, request RunRequest) (EngineResult, error) {
	e.rec.record("run")

	spec, _ := os.ReadFile(request.SpecPath)
	var mode fs.FileMode
	if info, err := os.Stat(request.SpecPath); err == nil {
		mode = info.Mode().Perm()
	}
	e.mu.Lock()
	e.lastRequest = request
	e.specSeen = spec
	e.specMode = mode
	e.mu.Unlock()

	return e.run(ctx, request)
}

// engineResult is what the fake packer reports; createdResult is what the
// blob store reports back. They differ so a test can tell which one reached
// the succeeded event.
var (
	engineResult = EngineResult{
		FileName:    "output",
		ContentType: "application/octet-stream",
		SizeBytes:   13,
		Checksum:    "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
	}
	createdResult = StoredResult{
		Engine: EngineProvenance{Version: testEngineVer, Checksum: testEngineSum},
		Result: EngineResult{
			FileName:    "output",
			ContentType: "application/octet-stream",
			SizeBytes:   99,
			Checksum:    "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
		},
	}
	storedResult = StoredResult{
		Engine: EngineProvenance{Version: "packer 0.2.0", Checksum: strings.Repeat("e", 64)},
		Result: EngineResult{
			FileName:    "output",
			ContentType: "application/octet-stream",
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
	queue     *fakeQueue
	artifacts *fakeArtifacts
	engine    *fakeEngine
	workRoot  string
	interval  time.Duration
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	rec := &recorder{}
	return &harness{
		rec: rec,
		queue: &fakeQueue{
			rec:      rec,
			delivery: fakeDelivery{body: dispatchBody()},
			sendErrs: map[string]error{},
		},
		artifacts: &fakeArtifacts{
			rec:     rec,
			request: requestBody(),
			created: cloneResult(createdResult),
		},
		engine: &fakeEngine{
			rec:        rec,
			provenance: EngineProvenance{Version: testEngineVer, Checksum: testEngineSum},
			run:        writeOutput,
		},
		workRoot: t.TempDir(),
		// An hour of renewal interval means no tick fires during a test that
		// asserts an exact call sequence, so the sequence stays the
		// processor's own.
		interval: time.Hour,
	}
}

func cloneResult(result StoredResult) *StoredResult {
	copied := result
	return &copied
}

func (h *harness) run() error {
	processor := NewProcessor(h.queue, h.artifacts, h.engine, h.workRoot, h.interval)
	return processor.RunOnce(context.Background())
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

func TestRunOnceSuccessCallsPortsInOrder(t *testing.T) {
	h := newHarness(t)

	if err := h.run(); err != nil {
		t.Fatalf("RunOnce: %v", err)
	}

	assertCalls(t, h.rec.snapshot(), []string{
		"receive", "findResult", "download", "provenance",
		"send:started", "run", "createResult", "send:succeeded", "complete",
	})
	assertWorkspaceRemoved(t, h.workRoot)

	events := h.queue.sentEvents()
	if len(events) != 2 {
		t.Fatalf("expected started and succeeded, got %d events", len(events))
	}
	if events[0].JobID != testJobID || events[0].EngineVersion != testEngineVer || events[0].EngineChecksum != testEngineSum {
		t.Fatalf("started event does not carry the job and its engine: %+v", events[0])
	}
	// The succeeded event must report the metadata CreateResult returned,
	// not the metadata this delivery computed: a loser of the conditional
	// create has to report the winner's bytes.
	succeeded := events[1]
	if got, want := derefInt64(succeeded.ResultSizeBytes), createdResult.Result.SizeBytes; got != want {
		t.Fatalf("succeeded resultSizeBytes = %d, want the stored winner's %d", got, want)
	}
	if got, want := deref(succeeded.ResultChecksum), createdResult.Result.Checksum; got != want {
		t.Fatalf("succeeded resultChecksum = %q, want the stored winner's %q", got, want)
	}
	if got, want := deref(succeeded.ResultFileName), createdResult.Result.FileName; got != want {
		t.Fatalf("succeeded resultFileName = %q, want %q", got, want)
	}
	if got, want := deref(succeeded.ResultContentType), createdResult.Result.ContentType; got != want {
		t.Fatalf("succeeded resultContentType = %q, want %q", got, want)
	}
}

func TestRunOncePassesJobIdFromBodyToEveryPort(t *testing.T) {
	h := newHarness(t)

	if err := h.run(); err != nil {
		t.Fatalf("RunOnce: %v", err)
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

func TestRunOnceWritesSpecBytesVerbatim(t *testing.T) {
	h := newHarness(t)

	if err := h.run(); err != nil {
		t.Fatalf("RunOnce: %v", err)
	}

	h.engine.mu.Lock()
	defer h.engine.mu.Unlock()
	if got := string(h.engine.specSeen); got != testSpec {
		t.Fatalf("packer input is not the spec byte for byte:\n got:  %s\n want: %s", got, testSpec)
	}
	if runtime.GOOS != "windows" {
		if h.engine.specMode != 0o600 {
			t.Fatalf("packer input mode = %v, want 0600", h.engine.specMode)
		}
	}
}

func TestRunOnceBuildsRunRequestFromEnvelope(t *testing.T) {
	h := newHarness(t)

	if err := h.run(); err != nil {
		t.Fatalf("RunOnce: %v", err)
	}

	h.engine.mu.Lock()
	request := h.engine.lastRequest
	h.engine.mu.Unlock()

	if request.Runtime != testMaxRuntime*time.Second {
		t.Fatalf("run runtime = %s, want %ds from the envelope", request.Runtime, testMaxRuntime)
	}
	if base := filepath.Base(request.SpecPath); base != "input.json" {
		t.Fatalf("spec path = %s, want a file named input.json", request.SpecPath)
	}
	workspace := filepath.Dir(request.SpecPath)
	if filepath.Dir(request.OutputPath) != workspace {
		t.Fatalf("output %s is not beside the spec %s", request.OutputPath, request.SpecPath)
	}
	if filepath.Dir(workspace) != h.workRoot {
		t.Fatalf("workspace %s is not directly under the configured root %s", workspace, h.workRoot)
	}
	if prefix := "packing-" + testJobID + "-"; !strings.HasPrefix(filepath.Base(workspace), prefix) {
		t.Fatalf("workspace %s does not start with %q", workspace, prefix)
	}

	h.artifacts.mu.Lock()
	defer h.artifacts.mu.Unlock()
	if filepath.Dir(h.artifacts.downloadPath) != workspace || filepath.Base(h.artifacts.downloadPath) != "request.json" {
		t.Fatalf("request downloaded to %s, want request.json inside %s", h.artifacts.downloadPath, workspace)
	}
	if h.artifacts.createPath != request.OutputPath {
		t.Fatalf("uploaded %s, want the packer output %s", h.artifacts.createPath, request.OutputPath)
	}
	if h.artifacts.createOffered.Result != engineResult {
		t.Fatalf("offered result %+v, want the engine's %+v", h.artifacts.createOffered.Result, engineResult)
	}
	if h.artifacts.createOffered.Engine != h.engine.provenance {
		t.Fatalf("offered engine %+v, want %+v", h.artifacts.createOffered.Engine, h.engine.provenance)
	}
}

func TestRunOnceEventsMatchTheContractEncoders(t *testing.T) {
	h := newHarness(t)

	if err := h.run(); err != nil {
		t.Fatalf("RunOnce: %v", err)
	}

	events := h.queue.sentEvents()
	started, err := contracts.EncodeStarted(testJobID, testEngineVer, testEngineSum)
	if err != nil {
		t.Fatalf("EncodeStarted: %v", err)
	}
	assertEventJSON(t, events[0], started)

	result := createdResult.Result
	succeeded, err := contracts.EncodeSucceeded(testJobID, testEngineVer, testEngineSum,
		result.FileName, result.ContentType, result.SizeBytes, result.Checksum)
	if err != nil {
		t.Fatalf("EncodeSucceeded: %v", err)
	}
	assertEventJSON(t, events[1], succeeded)
}

func assertEventJSON(t *testing.T, event contracts.WorkerEvent, want []byte) {
	t.Helper()
	got, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("marshal event: %v", err)
	}
	if string(got) != string(want) {
		t.Fatalf("event wire form drifted from the contract encoder:\n got:  %s\n want: %s", got, want)
	}
}

func TestRunOnceEngineFailureReportsFailedAndCompletes(t *testing.T) {
	for _, tc := range []struct {
		name   string
		reason string
	}{
		{name: "nonzero exit", reason: "packer exited with code 2"},
		{name: "runtime timeout", reason: "packing runtime limit of 60s exceeded"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			h.engine.run = func(context.Context, RunRequest) (EngineResult, error) {
				return EngineResult{}, &EngineFailure{Reason: tc.reason}
			}

			if err := h.run(); err != nil {
				t.Fatalf("a failed packing job is a normal outcome, got error: %v", err)
			}

			calls := h.rec.snapshot()
			assertCalls(t, calls, []string{
				"receive", "findResult", "download", "provenance",
				"send:started", "run", "send:failed", "complete",
			})
			assertNoCalls(t, calls, "createResult", "send:succeeded", "abandon")
			assertWorkspaceRemoved(t, h.workRoot)

			events := h.queue.sentEvents()
			failed := events[len(events)-1]
			if deref(failed.Reason) != tc.reason {
				t.Fatalf("failed reason = %q, want %q", deref(failed.Reason), tc.reason)
			}
			encoded, err := contracts.EncodeFailed(testJobID, testEngineVer, testEngineSum, tc.reason)
			if err != nil {
				t.Fatalf("EncodeFailed: %v", err)
			}
			assertEventJSON(t, failed, encoded)
		})
	}
}

func TestRunOnceNeverReportsABlankFailureReason(t *testing.T) {
	h := newHarness(t)
	h.engine.run = func(context.Context, RunRequest) (EngineResult, error) {
		return EngineResult{}, &EngineFailure{Reason: ""}
	}

	if err := h.run(); err != nil {
		t.Fatalf("RunOnce: %v", err)
	}

	events := h.queue.sentEvents()
	// The Java decoder rejects a blank reason outright, which would wedge
	// the job in RUNNING until the dead-letter reconciler noticed.
	if reason := deref(events[len(events)-1].Reason); strings.TrimSpace(reason) == "" {
		t.Fatal("failed event carries a blank reason")
	}
}

func TestRunOncePreExistingResultResendsBothEvents(t *testing.T) {
	h := newHarness(t)
	h.artifacts.find = cloneResult(storedResult)

	if err := h.run(); err != nil {
		t.Fatalf("RunOnce: %v", err)
	}

	calls := h.rec.snapshot()
	assertCalls(t, calls, []string{"receive", "findResult", "send:started", "send:succeeded", "complete"})
	assertNoCalls(t, calls, "download", "provenance", "run", "createResult", "abandon")
	assertWorkspaceRemoved(t, h.workRoot)

	events := h.queue.sentEvents()
	for _, event := range events {
		if event.EngineVersion != storedResult.Engine.Version || event.EngineChecksum != storedResult.Engine.Checksum {
			t.Fatalf("event %s does not carry the stored engine: %+v", event.EventType, event)
		}
	}
	succeeded := events[1]
	if deref(succeeded.ResultChecksum) != storedResult.Result.Checksum ||
		derefInt64(succeeded.ResultSizeBytes) != storedResult.Result.SizeBytes ||
		deref(succeeded.ResultFileName) != storedResult.Result.FileName ||
		deref(succeeded.ResultContentType) != storedResult.Result.ContentType {
		t.Fatalf("succeeded event does not carry the stored result: %+v", succeeded)
	}
}

var errInjected = errors.New("injected failure")

func TestRunOnceInfrastructureFailuresAbandon(t *testing.T) {
	for _, tc := range []struct {
		name      string
		arrange   func(*harness)
		wantCalls []string
		wantErr   error
	}{
		{
			name:      "malformed dispatch body",
			arrange:   func(h *harness) { h.queue.delivery = fakeDelivery{body: []byte(`{"messageVersion":1,"jobId":"nope"}`)} },
			wantCalls: []string{"receive", "abandon"},
			wantErr:   contracts.ErrInvalidJobID,
		},
		{
			name:      "dispatch body is not json",
			arrange:   func(h *harness) { h.queue.delivery = fakeDelivery{body: []byte("{")} },
			wantCalls: []string{"receive", "abandon"},
			wantErr:   contracts.ErrInvalidJSON,
		},
		{
			name:      "find result fails",
			arrange:   func(h *harness) { h.artifacts.findErr = errInjected },
			wantCalls: []string{"receive", "findResult", "abandon"},
			wantErr:   errInjected,
		},
		{
			name:      "download fails",
			arrange:   func(h *harness) { h.artifacts.downloadErr = errInjected },
			wantCalls: []string{"receive", "findResult", "download", "abandon"},
			wantErr:   errInjected,
		},
		{
			name:      "malformed request envelope",
			arrange:   func(h *harness) { h.artifacts.request = []byte("not an envelope") },
			wantCalls: []string{"receive", "findResult", "download", "abandon"},
			wantErr:   contracts.ErrInvalidJSON,
		},
		{
			name: "unsupported request version",
			arrange: func(h *harness) {
				h.artifacts.request = []byte(`{"requestVersion":2,"maxRuntimeSeconds":60,"spec":{}}`)
			},
			wantCalls: []string{"receive", "findResult", "download", "abandon"},
			wantErr:   contracts.ErrUnsupportedVersion,
		},
		{
			name:      "provenance fails",
			arrange:   func(h *harness) { h.engine.provenanceErr = errInjected },
			wantCalls: []string{"receive", "findResult", "download", "provenance", "abandon"},
			wantErr:   errInjected,
		},
		{
			name:      "started event fails",
			arrange:   func(h *harness) { h.queue.sendErrs["started"] = errInjected },
			wantCalls: []string{"receive", "findResult", "download", "provenance", "send:started", "abandon"},
			wantErr:   errInjected,
		},
		{
			name: "packer error that is not an engine failure",
			arrange: func(h *harness) {
				h.engine.run = func(context.Context, RunRequest) (EngineResult, error) {
					return EngineResult{}, errInjected
				}
			},
			wantCalls: []string{"receive", "findResult", "download", "provenance", "send:started", "run", "abandon"},
			wantErr:   errInjected,
		},
		{
			name:    "upload fails",
			arrange: func(h *harness) { h.artifacts.createErr = errInjected },
			wantCalls: []string{"receive", "findResult", "download", "provenance",
				"send:started", "run", "createResult", "abandon"},
			wantErr: errInjected,
		},
		{
			name:    "upload reports no metadata",
			arrange: func(h *harness) { h.artifacts.created = nil },
			wantCalls: []string{"receive", "findResult", "download", "provenance",
				"send:started", "run", "createResult", "abandon"},
		},
		{
			name:    "succeeded event fails",
			arrange: func(h *harness) { h.queue.sendErrs["succeeded"] = errInjected },
			wantCalls: []string{"receive", "findResult", "download", "provenance",
				"send:started", "run", "createResult", "send:succeeded", "abandon"},
			wantErr: errInjected,
		},
		{
			name: "failed event fails",
			arrange: func(h *harness) {
				h.engine.run = func(context.Context, RunRequest) (EngineResult, error) {
					return EngineResult{}, &EngineFailure{Reason: "packer exited with code 2"}
				}
				h.queue.sendErrs["failed"] = errInjected
			},
			wantCalls: []string{"receive", "findResult", "download", "provenance",
				"send:started", "run", "send:failed", "abandon"},
			wantErr: errInjected,
		},
		{
			name: "pre-existing result cannot be reported",
			arrange: func(h *harness) {
				h.artifacts.find = cloneResult(storedResult)
				h.queue.sendErrs["started"] = errInjected
			},
			wantCalls: []string{"receive", "findResult", "send:started", "abandon"},
			wantErr:   errInjected,
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			tc.arrange(h)

			err := h.run()
			if err == nil {
				t.Fatal("RunOnce returned nil, want an infrastructure error")
			}
			if tc.wantErr != nil && !errors.Is(err, tc.wantErr) {
				t.Fatalf("RunOnce error = %v, want it to wrap %v", err, tc.wantErr)
			}
			calls := h.rec.snapshot()
			assertCalls(t, calls, tc.wantCalls)
			assertNoCalls(t, calls, "complete")
			assertWorkspaceRemoved(t, h.workRoot)
		})
	}
}

func TestRunOnceNoMessage(t *testing.T) {
	h := newHarness(t)
	h.queue.receiveErr = ErrNoMessage

	err := h.run()

	if !errors.Is(err, ErrNoMessage) {
		t.Fatalf("RunOnce error = %v, want ErrNoMessage", err)
	}
	assertCalls(t, h.rec.snapshot(), []string{"receive"})
}

func TestRunOnceNilDeliveryIsNoMessage(t *testing.T) {
	h := newHarness(t)
	h.queue.delivery = nil

	err := h.run()

	if !errors.Is(err, ErrNoMessage) {
		t.Fatalf("RunOnce error = %v, want ErrNoMessage", err)
	}
	assertCalls(t, h.rec.snapshot(), []string{"receive"})
}

func TestRunOnceReceiveFailureIsNotSettled(t *testing.T) {
	h := newHarness(t)
	h.queue.receiveErr = errInjected

	err := h.run()

	if !errors.Is(err, errInjected) {
		t.Fatalf("RunOnce error = %v, want the receive failure", err)
	}
	assertCalls(t, h.rec.snapshot(), []string{"receive"})
}

func TestRunOnceReportsAFailedAbandonWithoutLosingTheCause(t *testing.T) {
	h := newHarness(t)
	h.artifacts.findErr = errInjected
	abandonFailed := errors.New("abandon rejected")
	h.queue.abandonErr = abandonFailed

	err := h.run()

	if !errors.Is(err, errInjected) {
		t.Fatalf("RunOnce error = %v, want it to keep wrapping the cause", err)
	}
	if !errors.Is(err, abandonFailed) {
		t.Fatalf("RunOnce error = %v, want it to report the failed abandon too", err)
	}
}

func TestRunOnceCompleteFailureIsNotFollowedByAbandon(t *testing.T) {
	h := newHarness(t)
	h.queue.completeErr = errInjected

	err := h.run()

	if !errors.Is(err, errInjected) {
		t.Fatalf("RunOnce error = %v, want the complete failure", err)
	}
	calls := h.rec.snapshot()
	assertCalls(t, calls, []string{
		"receive", "findResult", "download", "provenance",
		"send:started", "run", "createResult", "send:succeeded", "complete",
	})
	assertNoCalls(t, calls, "abandon")
}

func TestRunOnceLostLockCancelsTheEngineAndSettlesNothing(t *testing.T) {
	h := newHarness(t)
	h.interval = time.Millisecond
	h.queue.renewErr = errInjected

	// RenewLock is held until the packer is running, so the lock is always
	// lost mid-run rather than at whichever step the ticker happened to
	// catch.
	running := make(chan struct{})
	h.queue.renewGate = running

	canceled := make(chan struct{})
	h.engine.run = func(ctx context.Context, _ RunRequest) (EngineResult, error) {
		close(running)
		select {
		case <-ctx.Done():
			close(canceled)
			return EngineResult{}, fmt.Errorf("engine: packing canceled: %w", ctx.Err())
		case <-time.After(5 * time.Second):
			return EngineResult{}, errors.New("engine context was never canceled")
		}
	}

	err := h.run()

	if !errors.Is(err, ErrLockLost) {
		t.Fatalf("RunOnce error = %v, want ErrLockLost", err)
	}
	if !errors.Is(err, errInjected) {
		t.Fatalf("RunOnce error = %v, want it to wrap the renewal failure", err)
	}
	select {
	case <-canceled:
	default:
		t.Fatal("engine context was not canceled")
	}
	calls := h.rec.snapshot()
	assertCalls(t, calls, []string{
		"receive", "findResult", "download", "provenance", "send:started", "run", "renewLock",
	})
	assertNoCalls(t, calls, "createResult", "send:succeeded", "send:failed", "complete", "abandon")
	assertWorkspaceRemoved(t, h.workRoot)
}

func TestRunOnceLostLockSuppressesAnEngineFailure(t *testing.T) {
	h := newHarness(t)
	h.interval = time.Millisecond
	h.queue.renewErr = errInjected

	running := make(chan struct{})
	h.queue.renewGate = running
	// A packer that reports its own failure after the lock is gone must not
	// produce a terminal event: the job did not fail, the worker lost the
	// message. Task 2's runner checks cancellation before it classifies, so
	// this is defence in depth against an engine that does not.
	h.engine.run = func(ctx context.Context, _ RunRequest) (EngineResult, error) {
		close(running)
		select {
		case <-ctx.Done():
		case <-time.After(5 * time.Second):
			return EngineResult{}, errors.New("engine context was never canceled")
		}
		return EngineResult{}, &EngineFailure{Reason: "packer exited with code 2"}
	}

	err := h.run()

	if !errors.Is(err, ErrLockLost) {
		t.Fatalf("RunOnce error = %v, want ErrLockLost", err)
	}
	calls := h.rec.snapshot()
	assertNoCalls(t, calls, "send:failed", "send:succeeded", "complete", "abandon")
}

func TestRunOnceStopsRenewalBeforeReturning(t *testing.T) {
	h := newHarness(t)
	h.interval = time.Millisecond

	var leaked atomic.Bool
	h.queue.onRenewAfterClose = func() { leaked.Store(true) }

	if err := h.run(); err != nil {
		t.Fatalf("RunOnce: %v", err)
	}
	h.queue.close()

	// Twenty renewal intervals is ample for a surviving goroutine to tick.
	time.Sleep(20 * time.Millisecond)
	if leaked.Load() {
		t.Fatal("lock renewal outlived RunOnce")
	}
}

func TestRunOnceRejectsANonPositiveRenewalInterval(t *testing.T) {
	h := newHarness(t)
	h.interval = 0

	if err := h.run(); err == nil {
		t.Fatal("RunOnce accepted a zero renewal interval")
	}
	assertCalls(t, h.rec.snapshot(), nil)
}

func deref(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

func derefInt64(value *int64) int64 {
	if value == nil {
		return 0
	}
	return *value
}
