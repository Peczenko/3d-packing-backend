package engine

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

const (
	helperEnabled    = "GO_WANT_HELPER_PROCESS"
	helperVersion    = "GO_HELPER_VERSION"
	helperNewlines   = "GO_HELPER_TRAILING_NEWLINES"
	helperArgvFile   = "GO_HELPER_ARGV_FILE"
	helperWriteOut   = "GO_HELPER_WRITE_OUTPUT"
	helperOutput     = "GO_HELPER_OUTPUT"
	helperStderrSize = "GO_HELPER_STDERR_BYTES"
	helperExitCode   = "GO_HELPER_EXIT_CODE"
	helperSleep      = "GO_HELPER_SLEEP"
	helperReadyFile  = "GO_HELPER_READY_FILE"
	helperTermFile   = "GO_HELPER_TERM_FILE"
	helperIgnoreTerm = "GO_HELPER_IGNORE_TERM"
)

// TestMain re-execs this test binary as a stand-in packer. The runner owns
// the child's argv exactly — that is what TestRunPassesExactlyTheDocumentedArguments
// asserts — so the helper cannot be selected with the usual
// -test.run=TestHelperProcess argument and is dispatched from the
// environment before the testing framework parses any flags.
func TestMain(m *testing.M) {
	if os.Getenv(helperEnabled) == "1" {
		os.Exit(runHelper())
	}
	os.Exit(m.Run())
}

func runHelper() int {
	args := os.Args[1:]
	if path := os.Getenv(helperArgvFile); path != "" {
		if err := os.WriteFile(path, []byte(strings.Join(args, "\x00")), 0o600); err != nil {
			fmt.Fprintln(os.Stderr, "helper: record argv:", err)
			return 90
		}
	}

	if len(args) == 1 && args[0] == "--version" {
		newlines, _ := strconv.Atoi(os.Getenv(helperNewlines))
		fmt.Print(os.Getenv(helperVersion) + strings.Repeat("\n", newlines))
		return helperExitCodeValue()
	}

	flags := map[string]string{}
	for i := 0; i+1 < len(args); i += 2 {
		flags[args[i]] = args[i+1]
	}

	if size, _ := strconv.Atoi(os.Getenv(helperStderrSize)); size > 0 {
		if _, err := os.Stderr.Write(helperNoise(size)); err != nil {
			return 90
		}
	}

	if path := os.Getenv(helperTermFile); path != "" {
		received := make(chan os.Signal, 1)
		signal.Notify(received, syscall.SIGTERM)
		go func() {
			<-received
			_ = os.WriteFile(path, []byte("SIGTERM"), 0o600)
			if os.Getenv(helperIgnoreTerm) != "1" {
				os.Exit(0)
			}
		}()
	}

	if path := os.Getenv(helperReadyFile); path != "" {
		if err := os.WriteFile(path, []byte("ready"), 0o600); err != nil {
			fmt.Fprintln(os.Stderr, "helper: announce readiness:", err)
			return 90
		}
	}

	if sleep, err := time.ParseDuration(os.Getenv(helperSleep)); err == nil {
		time.Sleep(sleep)
	}

	if os.Getenv(helperWriteOut) == "1" {
		if err := os.WriteFile(flags["--output"], []byte(os.Getenv(helperOutput)), 0o600); err != nil {
			fmt.Fprintln(os.Stderr, "helper: write output:", err)
			return 90
		}
	}
	return helperExitCodeValue()
}

func helperExitCodeValue() int {
	code, _ := strconv.Atoi(os.Getenv(helperExitCode))
	return code
}

// helperNoise builds deterministic stderr bytes ending in a newline, so a
// test can recompute the exact tail the runner is expected to retain.
func helperNoise(size int) []byte {
	const alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
	noise := make([]byte, size)
	for i := range noise {
		noise[i] = alphabet[i%len(alphabet)]
	}
	if size > 0 {
		noise[size-1] = '\n'
	}
	return noise
}

func testRunner(t *testing.T) *Runner {
	t.Helper()
	executable, err := os.Executable()
	if err != nil {
		t.Fatalf("os.Executable: %v", err)
	}
	t.Setenv(helperEnabled, "1")
	return NewRunner(executable)
}

func testRequest(t *testing.T, runtime time.Duration) pipeline.RunRequest {
	t.Helper()
	dir := t.TempDir()
	request := pipeline.RunRequest{
		SpecPath:   filepath.Join(dir, "input.json"),
		OutputPath: filepath.Join(dir, "output.json"),
		Runtime:    runtime,
	}
	writeFile(t, request.SpecPath, `{"boxes":[]}`)
	return request
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

func readFile(t *testing.T, path string) string {
	t.Helper()
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return string(content)
}

func waitForFile(t *testing.T, path string) {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(path); err == nil {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", path)
}

func sha256File(t *testing.T, path string) string {
	t.Helper()
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	digest := sha256.Sum256(content)
	return hex.EncodeToString(digest[:])
}

func requireEngineFailure(t *testing.T, err error) *pipeline.EngineFailure {
	t.Helper()
	var failure *pipeline.EngineFailure
	if !errors.As(err, &failure) {
		t.Fatalf("expected *pipeline.EngineFailure, got %T: %v", err, err)
	}
	return failure
}

func requireInfrastructureError(t *testing.T, err error) {
	t.Helper()
	if err == nil {
		t.Fatal("expected an error, got nil")
	}
	var failure *pipeline.EngineFailure
	if errors.As(err, &failure) {
		t.Fatalf("infrastructure error was classified as an engine failure, so the processor would report the job failed instead of retrying: %v", err)
	}
}

func TestProvenanceTrimsExactlyOneTrailingNewline(t *testing.T) {
	for _, tc := range []struct {
		name     string
		newlines string
		want     string
	}{
		{"one trailing newline", "1", "packer 1.2.3"},
		{"two trailing newlines", "2", "packer 1.2.3\n"},
		{"no trailing newline", "0", "packer 1.2.3"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			runner := testRunner(t)
			t.Setenv(helperVersion, "packer 1.2.3")
			t.Setenv(helperNewlines, tc.newlines)

			provenance, err := runner.Provenance(context.Background())
			if err != nil {
				t.Fatalf("Provenance: %v", err)
			}
			if provenance.Version != tc.want {
				t.Fatalf("Version = %q, want %q", provenance.Version, tc.want)
			}
		})
	}
}

func TestProvenanceHashesTheExecutableNotTheVersionOutput(t *testing.T) {
	runner := testRunner(t)
	t.Setenv(helperVersion, "packer 1.2.3")
	t.Setenv(helperNewlines, "1")

	provenance, err := runner.Provenance(context.Background())
	if err != nil {
		t.Fatalf("Provenance: %v", err)
	}

	executable, err := os.Executable()
	if err != nil {
		t.Fatalf("os.Executable: %v", err)
	}
	if want := sha256File(t, executable); provenance.Checksum != want {
		t.Fatalf("Checksum = %q, want the SHA-256 of %s (%q)", provenance.Checksum, executable, want)
	}

	printed := sha256.Sum256([]byte("packer 1.2.3\n"))
	if provenance.Checksum == hex.EncodeToString(printed[:]) {
		t.Fatal("Checksum hashes the --version output rather than the executable's bytes")
	}
	if len(provenance.Checksum) != 64 || provenance.Checksum != strings.ToLower(provenance.Checksum) {
		t.Fatalf("Checksum = %q, want 64 lowercase hex characters", provenance.Checksum)
	}
}

func TestProvenanceRejectsBlankVersion(t *testing.T) {
	runner := testRunner(t)
	t.Setenv(helperVersion, "   ")
	t.Setenv(helperNewlines, "1")

	_, err := runner.Provenance(context.Background())
	if !errors.Is(err, errBlankVersion) {
		t.Fatalf("expected errBlankVersion, got %v", err)
	}
	requireInfrastructureError(t, err)
}

func TestProvenanceFailureIsInfrastructure(t *testing.T) {
	t.Run("missing executable", func(t *testing.T) {
		runner := NewRunner(filepath.Join(t.TempDir(), "packer-does-not-exist"))
		_, err := runner.Provenance(context.Background())
		requireInfrastructureError(t, err)
	})

	t.Run("version command exits nonzero", func(t *testing.T) {
		runner := testRunner(t)
		t.Setenv(helperVersion, "packer 1.2.3")
		t.Setenv(helperNewlines, "1")
		t.Setenv(helperExitCode, "2")

		_, err := runner.Provenance(context.Background())
		requireInfrastructureError(t, err)
	})
}

// TestProvenanceBoundsVersionOutput guards against a packer that streams
// instead of printing one short line and exiting on --version: Run's
// stdout goes to os.DevNull, Run's stderr is capped by tailBuffer, and
// exec.ExitError.Stderr is capped by os/exec itself, but nothing bounded
// Provenance's own stdout read before headBuffer. It keeps the head, not
// the tail: unlike a stderr log where the newest lines matter, a --version
// stream is one line at the very front, so the head is the only part
// worth keeping — this test's exact-prefix assertion fails against a
// tail-retaining cap just as it fails against no cap at all.
func TestProvenanceBoundsVersionOutput(t *testing.T) {
	runner := testRunner(t)
	noise := helperNoise(3 * maxVersionBytes)
	t.Setenv(helperVersion, string(noise))
	t.Setenv(helperNewlines, "0")

	provenance, err := runner.Provenance(context.Background())
	if err != nil {
		t.Fatalf("Provenance: %v", err)
	}

	want := string(noise[:maxVersionBytes])
	if len(provenance.Version) != maxVersionBytes {
		t.Fatalf("Version is %d bytes, want exactly %d: stdout is not bounded", len(provenance.Version), maxVersionBytes)
	}
	if provenance.Version != want {
		t.Fatalf("Version is not the head of stdout: got %q", truncateForLog(provenance.Version))
	}
}

// Provenance hashes the file exec.LookPath found. Run has to execute that
// same file, or the checksum recorded against the job describes bytes that
// were never run — which is the entire point of recording it. Changing PATH
// between the two calls is the only way to tell a single shared resolution
// apart from two independent lookups that happen to agree.
func TestRunExecutesThePathProvenanceResolved(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the shim is a /bin/sh script; the production image is Linux, which is where this path runs")
	}

	dir := t.TempDir()
	shim := filepath.Join(dir, "packer")
	script := "#!/bin/sh\n" +
		"if [ \"$1\" = \"--version\" ]; then echo 'packer 9.9.9'; exit 0; fi\n" +
		"echo packed > \"$4\"\n"
	if err := os.WriteFile(shim, []byte(script), 0o700); err != nil {
		t.Fatalf("write shim: %v", err)
	}

	t.Setenv("PATH", dir)
	runner := NewRunner("packer")
	if _, err := runner.Provenance(context.Background()); err != nil {
		t.Fatalf("Provenance: %v", err)
	}

	// The packer is still exactly where Provenance found it; only the lookup
	// that would find it again is gone.
	t.Setenv("PATH", t.TempDir())

	if _, err := runner.Run(context.Background(), testRequest(t, 60*time.Second)); err != nil {
		t.Fatalf("Run repeated the lookup instead of using the resolved path: %v", err)
	}
}

func TestRunPassesExactlyTheDocumentedArguments(t *testing.T) {
	runner := testRunner(t)
	request := testRequest(t, 60*time.Second)
	argvPath := filepath.Join(filepath.Dir(request.SpecPath), "argv")
	t.Setenv(helperArgvFile, argvPath)
	t.Setenv(helperWriteOut, "1")
	t.Setenv(helperOutput, "packed")

	if _, err := runner.Run(context.Background(), request); err != nil {
		t.Fatalf("Run: %v", err)
	}

	got := strings.Split(readFile(t, argvPath), "\x00")
	want := []string{
		"--spec", request.SpecPath,
		"--output", request.OutputPath,
		"--time-limit-seconds", "60",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("argv = %q, want %q", got, want)
	}
}

func TestRunDescribesTheOutputFile(t *testing.T) {
	const content = `{"placements":[{"id":"a"}]}`
	runner := testRunner(t)
	request := testRequest(t, 60*time.Second)
	t.Setenv(helperWriteOut, "1")
	t.Setenv(helperOutput, content)

	result, err := runner.Run(context.Background(), request)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}

	digest := sha256.Sum256([]byte(content))
	want := pipeline.EngineResult{
		FileName:    "output.json",
		ContentType: "application/octet-stream",
		SizeBytes:   int64(len(content)),
		Checksum:    hex.EncodeToString(digest[:]),
	}
	if result != want {
		t.Fatalf("EngineResult = %+v, want %+v", result, want)
	}
}

func TestRunTimeoutIsAnEngineFailure(t *testing.T) {
	runner := testRunner(t)
	request := testRequest(t, time.Second)
	t.Setenv(helperTermFile, filepath.Join(filepath.Dir(request.SpecPath), "term"))
	t.Setenv(helperSleep, "5m")

	started := time.Now()
	_, err := runner.Run(context.Background(), request)
	elapsed := time.Since(started)

	failure := requireEngineFailure(t, err)
	if got, want := failure.Error(), "packing runtime limit of 1s exceeded"; got != want {
		t.Fatalf("reason = %q, want %q", got, want)
	}
	if elapsed > 30*time.Second {
		t.Fatalf("Run took %s after a 1s runtime limit", elapsed)
	}
}

// TestParentContextDeadlineIsNotAnEngineFailure guards the classification
// order in Run: a parent deadline must be checked, and must win, before the
// runtime-limit deadline is even considered. request.Runtime's own
// context.WithTimeout is derived from ctx, so when the parent's shorter
// deadline fires first, runCtx.Err() is ALSO context.DeadlineExceeded —
// indistinguishable from a genuine runtime-limit timeout by that check
// alone. If the two cases in Run's switch were swapped, this parent-caused
// cancellation would be misreported as the packing job exceeding its
// multi-minute runtime limit, so the worker would fail the job and
// terminate the delivery instead of abandoning it for redelivery. The
// existing cancellation tests use context.WithCancel, whose
// context.Canceled never matches the DeadlineExceeded branch regardless of
// order, so they cannot catch this; only a parent context.WithTimeout can.
func TestParentContextDeadlineIsNotAnEngineFailure(t *testing.T) {
	runner := testRunner(t)
	request := testRequest(t, 5*time.Minute)
	t.Setenv(helperSleep, "5m")

	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()

	started := time.Now()
	_, err := runner.Run(ctx, request)
	elapsed := time.Since(started)

	requireInfrastructureError(t, err)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("error %v does not wrap the parent context's DeadlineExceeded", err)
	}
	if elapsed > 30*time.Second {
		t.Fatalf("Run took %s after a 300ms parent deadline against a 5m runtime limit", elapsed)
	}
}

// Duration.String() renders 60s as "1m0s" and 7200s as "2h0m0s", so the
// message the contract calls for cannot be built from the Duration.
func TestTimeoutReasonFormatsSecondsNotADurationString(t *testing.T) {
	for _, tc := range []struct {
		runtime time.Duration
		want    string
	}{
		{60 * time.Second, "packing runtime limit of 60s exceeded"},
		{90 * time.Second, "packing runtime limit of 90s exceeded"},
		{7200 * time.Second, "packing runtime limit of 7200s exceeded"},
	} {
		if got := timeoutReason(limitSeconds(tc.runtime)); got != tc.want {
			t.Fatalf("timeoutReason for %s = %q, want %q", tc.runtime, got, tc.want)
		}
	}
}

func TestLimitSecondsNeverAsksForZero(t *testing.T) {
	for _, tc := range []struct {
		runtime time.Duration
		want    int64
	}{
		{time.Nanosecond, 1},
		{1500 * time.Millisecond, 2},
		{60 * time.Second, 60},
		{7200 * time.Second, 7200},
	} {
		if got := limitSeconds(tc.runtime); got != tc.want {
			t.Fatalf("limitSeconds(%s) = %d, want %d", tc.runtime, got, tc.want)
		}
	}
}

func TestRunNonzeroExitIsAnEngineFailureWithBoundedStderr(t *testing.T) {
	const stderrSize = 20 << 10
	runner := testRunner(t)
	request := testRequest(t, 60*time.Second)
	t.Setenv(helperStderrSize, strconv.Itoa(stderrSize))
	t.Setenv(helperExitCode, "3")

	_, err := runner.Run(context.Background(), request)

	failure := requireEngineFailure(t, err)
	const head = "packer exited with code 3: "
	message := failure.Error()
	if !strings.HasPrefix(message, head) {
		t.Fatalf("reason %q does not start with %q", truncateForLog(message), head)
	}

	noise := helperNoise(stderrSize)
	wantTail := strings.TrimRight(string(noise[len(noise)-maxStderrBytes:]), "\r\n")
	if !strings.HasSuffix(message, wantTail) {
		t.Fatalf("reason does not end with the last %d bytes of stderr: %q", maxStderrBytes, truncateForLog(message))
	}
	if len(message) != len(head)+len(wantTail) {
		t.Fatalf("reason is %d bytes, want %d: stderr is not bounded to %d bytes", len(message), len(head)+len(wantTail), maxStderrBytes)
	}
}

func TestExitReasonDescribesASignaledPacker(t *testing.T) {
	if got, want := exitReason(3, "exit status 3", ""), "packer exited with code 3"; got != want {
		t.Fatalf("exitReason = %q, want %q", got, want)
	}
	if got, want := exitReason(-1, "signal: killed", ""), "packer did not exit normally: signal: killed"; got != want {
		t.Fatalf("exitReason = %q, want %q", got, want)
	}
}

func TestRunOutputProblemsAreInfrastructureErrors(t *testing.T) {
	t.Run("no output file", func(t *testing.T) {
		runner := testRunner(t)
		request := testRequest(t, 60*time.Second)

		_, err := runner.Run(context.Background(), request)
		requireInfrastructureError(t, err)
	})

	t.Run("empty output file", func(t *testing.T) {
		runner := testRunner(t)
		request := testRequest(t, 60*time.Second)
		t.Setenv(helperWriteOut, "1")
		t.Setenv(helperOutput, "")

		_, err := runner.Run(context.Background(), request)
		requireInfrastructureError(t, err)
		if !strings.Contains(err.Error(), "empty") {
			t.Fatalf("error %q does not mention the empty output", err)
		}
	})

	t.Run("output path is a directory", func(t *testing.T) {
		runner := testRunner(t)
		request := testRequest(t, 60*time.Second)
		if err := os.Mkdir(request.OutputPath, 0o700); err != nil {
			t.Fatalf("mkdir: %v", err)
		}

		_, err := runner.Run(context.Background(), request)
		requireInfrastructureError(t, err)
		if !strings.Contains(err.Error(), "regular file") {
			t.Fatalf("error %q does not mention the file type", err)
		}
	})
}

func TestRunMissingExecutableIsInfrastructureError(t *testing.T) {
	runner := NewRunner(filepath.Join(t.TempDir(), "packer-does-not-exist"))
	_, err := runner.Run(context.Background(), pipeline.RunRequest{
		SpecPath:   "input.json",
		OutputPath: "output.json",
		Runtime:    time.Second,
	})
	requireInfrastructureError(t, err)
}

func TestRunRejectsNonPositiveRuntime(t *testing.T) {
	runner := testRunner(t)
	request := testRequest(t, 0)

	_, err := runner.Run(context.Background(), request)
	requireInfrastructureError(t, err)
}

func TestRunCancellationTerminatesTheChildGracefully(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("os.Process.Signal on Windows accepts only os.Kill, so there is no SIGTERM to observe; terminate_unix.go's path is exercised on Linux")
	}

	runner := testRunner(t)
	request := testRequest(t, 5*time.Minute)
	dir := filepath.Dir(request.SpecPath)
	readyPath := filepath.Join(dir, "ready")
	termPath := filepath.Join(dir, "term")
	t.Setenv(helperReadyFile, readyPath)
	t.Setenv(helperTermFile, termPath)
	t.Setenv(helperSleep, "5m")

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		_, err := runner.Run(ctx, request)
		done <- err
	}()

	waitForFile(t, readyPath)
	cancel()
	started := time.Now()

	select {
	case err := <-done:
		elapsed := time.Since(started)
		requireInfrastructureError(t, err)
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("error %v does not wrap context.Canceled", err)
		}
		if elapsed >= terminateGrace {
			t.Fatalf("Run took %s, so the child was killed after the grace period rather than exiting on SIGTERM", elapsed)
		}
	case <-time.After(30 * time.Second):
		t.Fatal("Run never returned after cancellation")
	}

	if got := readFile(t, termPath); got != "SIGTERM" {
		t.Fatalf("child recorded %q, want SIGTERM", got)
	}
}

func TestRunKillsAChildThatIgnoresTermination(t *testing.T) {
	runner := testRunner(t)
	request := testRequest(t, 5*time.Minute)
	dir := filepath.Dir(request.SpecPath)
	readyPath := filepath.Join(dir, "ready")
	termPath := filepath.Join(dir, "term")
	t.Setenv(helperReadyFile, readyPath)
	t.Setenv(helperTermFile, termPath)
	t.Setenv(helperIgnoreTerm, "1")
	t.Setenv(helperSleep, "5m")

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		_, err := runner.Run(ctx, request)
		done <- err
	}()

	waitForFile(t, readyPath)
	cancel()
	started := time.Now()

	var elapsed time.Duration
	select {
	case err := <-done:
		elapsed = time.Since(started)
		requireInfrastructureError(t, err)
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("error %v does not wrap context.Canceled", err)
		}
	case <-time.After(60 * time.Second):
		t.Fatal("Run never returned: a packer that ignores termination is never killed")
	}

	t.Run("terminated before being killed", func(t *testing.T) {
		if runtime.GOOS == "windows" {
			t.Skip("Windows cancels by killing outright, so no SIGTERM is delivered and no grace period applies")
		}
		if got := readFile(t, termPath); got != "SIGTERM" {
			t.Fatalf("child recorded %q, want SIGTERM before the kill", got)
		}
		if elapsed < terminateGrace-time.Second {
			t.Fatalf("child was killed after %s, before the %s termination grace elapsed", elapsed, terminateGrace)
		}
		// Pinned to the literal 5-second budget, not the terminateGrace
		// constant: the requirement is "wait at most five seconds" before
		// killing, independent of whatever the constant currently reads. A
		// canceled packer must not be able to hold a Service Bus lock for
		// however long a future change happens to set terminateGrace to.
		if want := 5 * time.Second; elapsed > want+2*time.Second {
			t.Fatalf("child was killed after %s, want at most %s", elapsed, want)
		}
	})
}

func TestTailBufferKeepsOnlyTheLastBytes(t *testing.T) {
	noise := helperNoise(3 * maxStderrBytes)
	want := string(noise[len(noise)-maxStderrBytes:])

	t.Run("many small writes", func(t *testing.T) {
		var buffer tailBuffer
		for offset := 0; offset < len(noise); offset += 777 {
			end := min(offset+777, len(noise))
			n, err := buffer.Write(noise[offset:end])
			if err != nil || n != end-offset {
				t.Fatalf("Write = (%d, %v), want (%d, nil)", n, err, end-offset)
			}
		}
		if got := buffer.String(); got != want {
			t.Fatalf("retained %d bytes ending %q, want the last %d bytes", len(got), truncateForLog(got), maxStderrBytes)
		}
	})

	t.Run("one oversized write", func(t *testing.T) {
		var buffer tailBuffer
		n, err := buffer.Write(noise)
		if err != nil || n != len(noise) {
			t.Fatalf("Write = (%d, %v), want (%d, nil)", n, err, len(noise))
		}
		if got := buffer.String(); got != want {
			t.Fatalf("retained %d bytes, want the last %d", len(got), maxStderrBytes)
		}
	})

	t.Run("short input is kept whole", func(t *testing.T) {
		var buffer tailBuffer
		if _, err := buffer.Write([]byte("boom")); err != nil {
			t.Fatalf("Write: %v", err)
		}
		if got := buffer.String(); got != "boom" {
			t.Fatalf("retained %q, want %q", got, "boom")
		}
	})
}

func truncateForLog(s string) string {
	const limit = 120
	if len(s) <= limit {
		return s
	}
	return s[:limit/2] + "…" + s[len(s)-limit/2:]
}
