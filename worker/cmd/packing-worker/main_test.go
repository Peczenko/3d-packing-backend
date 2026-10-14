package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/config"
)

const testSecretMarker = "SHARED-KEY-MUST-NEVER-BE-LOGGED"

func useTestEnvironment(t *testing.T) {
	t.Helper()
	t.Setenv("TEMPORAL_ADDRESS", "temporal.test:7233")
	t.Setenv("TEMPORAL_NAMESPACE", "test-namespace")
	t.Setenv("PACKING_TASK_QUEUE", "test-engine")
	t.Setenv("PACKING_HEARTBEAT_INTERVAL", "7s")
	t.Setenv("STORAGE_CONNECTION_STRING", "DefaultEndpointsProtocol=https;AccountName=fake;AccountKey="+testSecretMarker)
	t.Setenv("STORAGE_ACCOUNT_URL", "")
	t.Setenv("STORAGE_CONTAINER_NAME", "test-models")
	t.Setenv("PACKER_PATH", "/test/bin/packer")
	t.Setenv("PACKING_WORK_ROOT", t.TempDir())
}

type fakeRunner struct {
	err    error
	ran    bool
	ctxErr error
}

func (r *fakeRunner) Run(ctx context.Context) error {
	r.ran = true
	<-ctx.Done()
	r.ctxErr = ctx.Err()
	return r.err
}

type connectorSpy struct {
	calls int
	cfg   config.Config
}

func (s *connectorSpy) serving(r runner) connector {
	return func(_ context.Context, cfg config.Config, _ *slog.Logger) (runner, error) {
		s.calls++
		s.cfg = cfg
		return r, nil
	}
}

func (s *connectorSpy) failing(err error) connector {
	return func(_ context.Context, cfg config.Config, _ *slog.Logger) (runner, error) {
		s.calls++
		s.cfg = cfg
		return nil, err
	}
}

func cancelled() context.Context {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	return ctx
}

func logLines(t *testing.T, out *bytes.Buffer) []map[string]any {
	t.Helper()
	var lines []map[string]any
	scanner := bufio.NewScanner(bytes.NewReader(out.Bytes()))
	for scanner.Scan() {
		var line map[string]any
		if err := json.Unmarshal(scanner.Bytes(), &line); err != nil {
			t.Fatalf("log line is not JSON: %q: %v", scanner.Text(), err)
		}
		lines = append(lines, line)
	}
	return lines
}

func TestRunRejectsCommandLineArgumentsWithoutConnecting(t *testing.T) {
	useTestEnvironment(t)
	spy := &connectorSpy{}
	var out bytes.Buffer

	if code := runWith(context.Background(), []string{"--once"}, &out, spy.serving(&fakeRunner{})); code != 2 {
		t.Fatalf("exit code = %d, want 2", code)
	}
	if spy.calls != 0 {
		t.Fatalf("connector called %d times, want 0", spy.calls)
	}
}

func TestRunWithReportsAConfigurationFailureAsExitTwo(t *testing.T) {
	useTestEnvironment(t)
	t.Setenv("STORAGE_CONNECTION_STRING", "")
	spy := &connectorSpy{}
	var out bytes.Buffer

	if code := runWith(context.Background(), nil, &out, spy.serving(&fakeRunner{})); code != 2 {
		t.Fatalf("exit code = %d, want 2", code)
	}
	if spy.calls != 0 {
		t.Fatalf("connector called %d times, want 0", spy.calls)
	}
}

func TestRunWithPassesTheLoadedConfigurationToTheConnector(t *testing.T) {
	useTestEnvironment(t)
	spy := &connectorSpy{}
	var out bytes.Buffer

	runWith(cancelled(), nil, &out, spy.serving(&fakeRunner{}))

	want := config.Config{
		TemporalAddress:   "temporal.test:7233",
		TemporalNamespace: "test-namespace",
		TaskQueue:         "test-engine",
		HeartbeatInterval: 7 * time.Second,
		StorageContainer:  "test-models",
		PackerPath:        "/test/bin/packer",
	}
	got := spy.cfg
	got.StorageConnectionString = ""
	got.WorkRoot = ""
	if got != want {
		t.Fatalf("connector received %+v, want %+v", got, want)
	}
}

func TestRunWithExitsZeroWhenTheWorkerStopsOnShutdown(t *testing.T) {
	useTestEnvironment(t)
	r := &fakeRunner{}
	var out bytes.Buffer

	if code := runWith(cancelled(), nil, &out, (&connectorSpy{}).serving(r)); code != 0 {
		t.Fatalf("exit code = %d, want 0", code)
	}
	if !r.ran || !errors.Is(r.ctxErr, context.Canceled) {
		t.Fatalf("worker ran=%t with ctx error %v, want it to run until shutdown", r.ran, r.ctxErr)
	}
}

func TestRunWithExitsOneWhenTheWorkerFails(t *testing.T) {
	useTestEnvironment(t)
	var out bytes.Buffer

	code := runWith(cancelled(), nil, &out, (&connectorSpy{}).serving(&fakeRunner{err: errors.New("poller crashed")}))

	if code != 1 {
		t.Fatalf("exit code = %d, want 1", code)
	}
}

func TestRunWithTreatsShutdownWhileConnectingAsClean(t *testing.T) {
	useTestEnvironment(t)
	var out bytes.Buffer

	code := runWith(cancelled(), nil, &out, (&connectorSpy{}).failing(context.Canceled))

	if code != 0 {
		t.Fatalf("exit code = %d, want 0", code)
	}
}

func TestRunWithReportsAWorkerThatCannotBeBuiltAsExitTwo(t *testing.T) {
	useTestEnvironment(t)
	var out bytes.Buffer

	code := runWith(context.Background(), nil, &out, (&connectorSpy{}).failing(errors.New("bad storage url")))

	if code != 2 {
		t.Fatalf("exit code = %d, want 2", code)
	}
}

func TestLogLinesAreJSONAndNeverCarryACredential(t *testing.T) {
	useTestEnvironment(t)
	var out bytes.Buffer

	runWith(context.Background(), nil, &out, (&connectorSpy{}).failing(errors.New("bad storage url")))
	runWith(cancelled(), nil, &out, (&connectorSpy{}).serving(&fakeRunner{}))

	lines := logLines(t, &out)
	if len(lines) == 0 {
		t.Fatal("expected log output")
	}
	for _, line := range lines {
		if _, ok := line["msg"]; !ok {
			t.Fatalf("log line has no msg field: %v", line)
		}
	}
	if strings.Contains(out.String(), testSecretMarker) {
		t.Fatalf("log output leaks the storage connection string:\n%s", out.String())
	}
}

func TestDialStopsRetryingWhenShutdownIsRequested(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	var out bytes.Buffer
	cfg := config.Config{TemporalAddress: "127.0.0.1:1", TemporalNamespace: "packing"}

	started := time.Now()
	_, err := dial(ctx, cfg, slog.New(slog.NewJSONHandler(&out, nil)))

	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("dial returned %v, want the shutdown deadline", err)
	}
	if elapsed := time.Since(started); elapsed > 3*time.Second {
		t.Fatalf("dial kept retrying for %s after shutdown", elapsed)
	}
	if !strings.Contains(out.String(), "temporal is not reachable yet") {
		t.Fatalf("dial failure was not logged:\n%s", out.String())
	}
}
