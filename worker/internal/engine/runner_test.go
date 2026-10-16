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

const testVersion = "v0.0.2"

const (
	helperEnabled    = "GO_WANT_HELPER_PROCESS"
	helperArgvFile   = "GO_HELPER_ARGV_FILE"
	helperEchoArgs   = "GO_HELPER_ECHO_ARGS"
	helperWriteOut   = "GO_HELPER_WRITE_OUTPUT"
	helperOutput     = "GO_HELPER_OUTPUT"
	helperStderrSize = "GO_HELPER_STDERR_BYTES"
	helperExitCode   = "GO_HELPER_EXIT_CODE"
	helperSleep      = "GO_HELPER_SLEEP"
	helperReadyFile  = "GO_HELPER_READY_FILE"
	helperTermFile   = "GO_HELPER_TERM_FILE"
	helperIgnoreTerm = "GO_HELPER_IGNORE_TERM"
)

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

	output := ""
	for i := 0; i+1 < len(args); i++ {
		if args[i] == "-o" {
			output = args[i+1]
		}
	}

	if os.Getenv(helperEchoArgs) == "1" {
		fmt.Fprint(os.Stderr, strings.Join(args, " "))
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
		if err := os.WriteFile(output, []byte(os.Getenv(helperOutput)), 0o600); err != nil {
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

func testRunnerWithGrace(t *testing.T, grace time.Duration) *Runner {
	t.Helper()
	executable, err := os.Executable()
	if err != nil {
		t.Fatalf("os.Executable: %v", err)
	}
	t.Setenv(helperEnabled, "1")
	return NewRunner(executable, testVersion, grace)
}

func testRunner(t *testing.T) *Runner {
	t.Helper()
	return testRunnerWithGrace(t, 0)
}

func testRequest(t *testing.T, runtime time.Duration) pipeline.RunRequest {
	t.Helper()
	dir := t.TempDir()
	inputs := filepath.Join(dir, "inputs")
	if err := os.Mkdir(inputs, 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	input := filepath.Join(inputs, "0.obj")
	writeFile(t, input, "v 0 0 0\n")
	return pipeline.RunRequest{
		Inputs:     []pipeline.InputFile{{Path: input, DisplayName: "bunny.obj", Copies: 1}},
		OutputPath: filepath.Join(dir, "packed.obj"),
		Runtime:    runtime,
	}
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

func TestProvenanceReportsThePinnedVersionAndTheExecutablesDigest(t *testing.T) {
	runner := testRunner(t)

	provenance, err := runner.Provenance(context.Background())
	if err != nil {
		t.Fatalf("Provenance: %v", err)
	}

	executable, err := os.Executable()
	if err != nil {
		t.Fatalf("os.Executable: %v", err)
	}
	want := pipeline.EngineProvenance{Version: testVersion, Checksum: sha256File(t, executable)}
	if provenance != want {
		t.Fatalf("Provenance = %+v, want %+v", provenance, want)
	}
}

func TestProvenanceRejectsABlankVersion(t *testing.T) {
	executable, err := os.Executable()
	if err != nil {
		t.Fatalf("os.Executable: %v", err)
	}

	_, err = NewRunner(executable, "  ", 0).Provenance(context.Background())
	if !errors.Is(err, errBlankVersion) {
		t.Fatalf("expected errBlankVersion, got %v", err)
	}
	requireInfrastructureError(t, err)
}

func TestProvenanceMissingExecutableIsInfrastructure(t *testing.T) {
	runner := NewRunner(filepath.Join(t.TempDir(), "packer-does-not-exist"), testVersion, 0)

	_, err := runner.Provenance(context.Background())
	requireInfrastructureError(t, err)
}

func TestRunExecutesThePathProvenanceResolved(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the shim is a /bin/sh script; the production image is Linux, which is where this path runs")
	}

	dir := t.TempDir()
	shim := filepath.Join(dir, "packer")
	script := "#!/bin/sh\necho packed > \"$4\"\n"
	if err := os.WriteFile(shim, []byte(script), 0o700); err != nil {
		t.Fatalf("write shim: %v", err)
	}

	t.Setenv("PATH", dir)
	runner := NewRunner("packer", testVersion, 0)
	if _, err := runner.Provenance(context.Background()); err != nil {
		t.Fatalf("Provenance: %v", err)
	}

	t.Setenv("PATH", t.TempDir())

	if _, err := runner.Run(context.Background(), testRequest(t, 60*time.Second)); err != nil {
		t.Fatalf("Run repeated the lookup instead of using the resolved path: %v", err)
	}
}

func TestRunPassesTheBudgetOutputParametersAndOnePathPerCopy(t *testing.T) {
	runner := testRunner(t)
	request := testRequest(t, 60*time.Second)
	second := filepath.Join(filepath.Dir(request.Inputs[0].Path), "1.obj")
	writeFile(t, second, "v 1 1 1\n")
	request.Inputs[0].Copies = 2
	request.Inputs = append(request.Inputs, pipeline.InputFile{Path: second, DisplayName: "bracket.obj", Copies: 1})
	request.ParameterArgs = []string{"-x", "1"}
	argvPath := filepath.Join(t.TempDir(), "argv")
	t.Setenv(helperArgvFile, argvPath)
	t.Setenv(helperWriteOut, "1")
	t.Setenv(helperOutput, "packed")

	if _, err := runner.Run(context.Background(), request); err != nil {
		t.Fatalf("Run: %v", err)
	}

	got := strings.Split(readFile(t, argvPath), "\x00")
	want := []string{
		"-t", "60",
		"-o", request.OutputPath,
		"-x", "1",
		request.Inputs[0].Path, request.Inputs[0].Path, second,
	}
	if !slices.Equal(got, want) {
		t.Fatalf("argv = %q, want %q", got, want)
	}
}

func TestRunDescribesTheOutputFile(t *testing.T) {
	const content = "o Object_1\nv 0 0 0\n"
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
		FileName:    "packed.obj",
		ContentType: "model/obj",
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
	t.Setenv(helperTermFile, filepath.Join(t.TempDir(), "term"))
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

func TestRunGivesThePackerItsGraceBeyondTheBudget(t *testing.T) {
	runner := testRunnerWithGrace(t, 5*time.Second)
	request := testRequest(t, time.Second)
	t.Setenv(helperSleep, "1500ms")
	t.Setenv(helperWriteOut, "1")
	t.Setenv(helperOutput, "packed")

	if _, err := runner.Run(context.Background(), request); err != nil {
		t.Fatalf("Run killed a packer still inside its grace period: %v", err)
	}
}

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

func TestRunNamesInputsByTheirFileNamesInAFailure(t *testing.T) {
	runner := testRunner(t)
	request := testRequest(t, 60*time.Second)
	second := filepath.Join(filepath.Dir(request.Inputs[0].Path), "1.obj")
	writeFile(t, second, "v 1 1 1\n")
	request.Inputs = append(request.Inputs, pipeline.InputFile{Path: second, DisplayName: "bracket.obj", Copies: 1})
	t.Setenv(helperEchoArgs, "1")
	t.Setenv(helperExitCode, "3")

	_, err := runner.Run(context.Background(), request)

	failure := requireEngineFailure(t, err)
	if got, want := failure.Error(), "packer exited with code 3: -t 60 -o packed.obj bunny.obj bracket.obj"; got != want {
		t.Fatalf("reason = %q, want %q", got, want)
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
	runner := NewRunner(filepath.Join(t.TempDir(), "packer-does-not-exist"), testVersion, 0)
	_, err := runner.Run(context.Background(), pipeline.RunRequest{
		Inputs:     []pipeline.InputFile{{Path: "0.obj", DisplayName: "bunny.obj", Copies: 1}},
		OutputPath: "packed.obj",
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

func TestRunRejectsARequestWithoutInputs(t *testing.T) {
	runner := testRunner(t)
	request := testRequest(t, 60*time.Second)
	request.Inputs = nil

	_, err := runner.Run(context.Background(), request)
	if !errors.Is(err, errNoInputs) {
		t.Fatalf("expected errNoInputs, got %v", err)
	}
	requireInfrastructureError(t, err)
}

func TestRunCancellationTerminatesTheChildGracefully(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("os.Process.Signal on Windows accepts only os.Kill, so there is no SIGTERM to observe; terminate_unix.go's path is exercised on Linux")
	}

	runner := testRunner(t)
	request := testRequest(t, 5*time.Minute)
	dir := t.TempDir()
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
	dir := t.TempDir()
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
