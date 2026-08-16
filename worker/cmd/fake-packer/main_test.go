package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

func validSpecPath(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "spec.json")
	writeFile(t, path, `{"boxes":[]}`)
	return path
}

func mustNotExist(t *testing.T, path string) {
	t.Helper()
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("expected %s not to exist, stat error: %v", path, err)
	}
}

func TestVersionPrintsExactlyOneTrailingNewline(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := run([]string{"--version"}, &stdout, &stderr)

	if code != 0 {
		t.Fatalf("exit code = %d, want 0", code)
	}
	if want := "fake-packer 0.1.0\n"; stdout.String() != want {
		t.Fatalf("stdout = %q, want %q", stdout.String(), want)
	}
	if stderr.Len() != 0 {
		t.Fatalf("stderr = %q, want empty", stderr.String())
	}
}

func TestMissingOrInvalidFlagsExitTwo(t *testing.T) {
	spec := validSpecPath(t)

	cases := []struct {
		name string
		args []string
	}{
		{"no arguments at all", nil},
		{"missing --spec", []string{"--output", "out.json", "--time-limit-seconds", "10"}},
		{"missing --output", []string{"--spec", spec, "--time-limit-seconds", "10"}},
		{"missing --time-limit-seconds", []string{"--spec", spec, "--output", "out.json"}},
		{"zero --time-limit-seconds", []string{"--spec", spec, "--output", "out.json", "--time-limit-seconds", "0"}},
		{"negative --time-limit-seconds", []string{"--spec", spec, "--output", "out.json", "--time-limit-seconds", "-5"}},
		{"non-numeric --time-limit-seconds", []string{"--spec", spec, "--output", "out.json", "--time-limit-seconds", "soon"}},
		{"unrecognised flag", []string{"--spec", spec, "--output", "out.json", "--time-limit-seconds", "10", "--bogus"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			outputPath := filepath.Join(t.TempDir(), "out.json")
			args := make([]string, len(tc.args))
			copy(args, tc.args)
			for i, a := range args {
				if a == "out.json" {
					args[i] = outputPath
				}
			}

			var stdout, stderr bytes.Buffer
			code := run(args, &stdout, &stderr)

			if code != 2 {
				t.Fatalf("exit code = %d, want 2 (stderr: %s)", code, stderr.String())
			}
			mustNotExist(t, outputPath)
		})
	}
}

func TestInvalidSpecJSONExitsOne(t *testing.T) {
	dir := t.TempDir()
	specPath := filepath.Join(dir, "spec.json")
	outputPath := filepath.Join(dir, "out.json")
	writeFile(t, specPath, `{not valid json`)

	var stdout, stderr bytes.Buffer
	code := run([]string{"--spec", specPath, "--output", outputPath, "--time-limit-seconds", "10"}, &stdout, &stderr)

	if code != 1 {
		t.Fatalf("exit code = %d, want 1 (stderr: %s)", code, stderr.String())
	}
	mustNotExist(t, outputPath)
	if stderr.Len() == 0 {
		t.Fatal("expected a stderr message describing the parse failure")
	}
}

func TestSpecFileMissingExitsOne(t *testing.T) {
	dir := t.TempDir()
	outputPath := filepath.Join(dir, "out.json")

	var stdout, stderr bytes.Buffer
	code := run([]string{"--spec", filepath.Join(dir, "does-not-exist.json"), "--output", outputPath, "--time-limit-seconds", "10"}, &stdout, &stderr)

	if code != 1 {
		t.Fatalf("exit code = %d, want 1 (stderr: %s)", code, stderr.String())
	}
	mustNotExist(t, outputPath)
}

func TestOutputWriteFailureExitsOne(t *testing.T) {
	dir := t.TempDir()
	specPath := filepath.Join(dir, "spec.json")
	writeFile(t, specPath, `{"a":1}`)
	// The parent directory does not exist, so the temp file can never be
	// created: a deterministic, cross-platform write failure.
	outputPath := filepath.Join(dir, "missing-parent", "out.json")

	var stdout, stderr bytes.Buffer
	code := run([]string{"--spec", specPath, "--output", outputPath, "--time-limit-seconds", "10"}, &stdout, &stderr)

	if code != 1 {
		t.Fatalf("exit code = %d, want 1 (stderr: %s)", code, stderr.String())
	}
	if stderr.Len() == 0 {
		t.Fatal("expected a stderr message describing the write failure")
	}
}

func TestSuccessEchoesSpecStripsFakeKeysAndMatchesGoldenFile(t *testing.T) {
	outputPath := filepath.Join(t.TempDir(), "output.json")

	var stdout, stderr bytes.Buffer
	code := run([]string{
		"--spec", filepath.Join("..", "..", "testdata", "spec.json"),
		"--output", outputPath,
		"--time-limit-seconds", "60",
	}, &stdout, &stderr)

	if code != 0 {
		t.Fatalf("exit code = %d, want 0 (stderr: %s)", code, stderr.String())
	}

	got, err := os.ReadFile(outputPath)
	if err != nil {
		t.Fatalf("read output: %v", err)
	}
	want, err := os.ReadFile(filepath.Join("..", "..", "testdata", "expected-output.json"))
	if err != nil {
		t.Fatalf("read golden file: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("output bytes do not match the golden file byte for byte:\n got:  %s\n want: %s", got, want)
	}

	if bytes.Contains(got, []byte("_fakeSleepSeconds")) {
		t.Fatal("echoed spec still carries _fakeSleepSeconds")
	}
	if bytes.Contains(got, []byte("_fakeExitCode")) {
		t.Fatal("echoed spec still carries _fakeExitCode")
	}
	mustNotExist(t, outputPath+".tmp")
}

func TestFakeExitCodeNonzeroOverridesExitAndSkipsOutput(t *testing.T) {
	dir := t.TempDir()
	specPath := filepath.Join(dir, "spec.json")
	outputPath := filepath.Join(dir, "out.json")
	writeFile(t, specPath, `{"_fakeExitCode":7,"keep":"me"}`)

	var stdout, stderr bytes.Buffer
	code := run([]string{"--spec", specPath, "--output", outputPath, "--time-limit-seconds", "10"}, &stdout, &stderr)

	if code != 7 {
		t.Fatalf("exit code = %d, want 7", code)
	}
	mustNotExist(t, outputPath)
}

func TestFakeExitCodeZeroIsIgnored(t *testing.T) {
	dir := t.TempDir()
	specPath := filepath.Join(dir, "spec.json")
	outputPath := filepath.Join(dir, "out.json")
	writeFile(t, specPath, `{"_fakeExitCode":0,"keep":"me"}`)

	var stdout, stderr bytes.Buffer
	code := run([]string{"--spec", specPath, "--output", outputPath, "--time-limit-seconds", "10"}, &stdout, &stderr)

	if code != 0 {
		t.Fatalf("exit code = %d, want 0", code)
	}
	info, err := os.Stat(outputPath)
	if err != nil {
		t.Fatalf("expected an output file, stat failed: %v", err)
	}
	if info.Size() == 0 {
		t.Fatal("output file is empty")
	}
	if bytes.Contains(mustRead(t, outputPath), []byte("_fakeExitCode")) {
		t.Fatal("echoed spec still carries _fakeExitCode")
	}
}

func mustRead(t *testing.T, path string) []byte {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return data
}

func TestSleepUnderTheTimeLimitCompletesNormally(t *testing.T) {
	dir := t.TempDir()
	specPath := filepath.Join(dir, "spec.json")
	outputPath := filepath.Join(dir, "out.json")
	writeFile(t, specPath, `{"_fakeSleepSeconds":0.2,"keep":"me"}`)

	started := time.Now()
	var stdout, stderr bytes.Buffer
	code := run([]string{"--spec", specPath, "--output", outputPath, "--time-limit-seconds", "5"}, &stdout, &stderr)
	elapsed := time.Since(started)

	if code != 0 {
		t.Fatalf("exit code = %d, want 0 (stderr: %s)", code, stderr.String())
	}
	if elapsed < 200*time.Millisecond {
		t.Fatalf("run returned after %s, want at least 200ms (the sleep was skipped)", elapsed)
	}
	if _, err := os.Stat(outputPath); err != nil {
		t.Fatalf("expected an output file: %v", err)
	}
}

func TestSleepAtOrOverTheTimeLimitTimesOut(t *testing.T) {
	dir := t.TempDir()
	specPath := filepath.Join(dir, "spec.json")
	outputPath := filepath.Join(dir, "out.json")
	writeFile(t, specPath, `{"_fakeSleepSeconds":5,"keep":"me"}`)

	started := time.Now()
	var stdout, stderr bytes.Buffer
	code := run([]string{"--spec", specPath, "--output", outputPath, "--time-limit-seconds", "1"}, &stdout, &stderr)
	elapsed := time.Since(started)

	if code != 1 {
		t.Fatalf("exit code = %d, want 1 (stderr: %s)", code, stderr.String())
	}
	if elapsed >= 3*time.Second {
		t.Fatalf("run took %s, want well under the 5s sleep request: the time limit was not enforced", elapsed)
	}
	if elapsed < 900*time.Millisecond {
		t.Fatalf("run took only %s, want close to the 1s limit: the timeout fired too early to be honouring the limit", elapsed)
	}
	if got, want := strings.TrimRight(stderr.String(), "\n"), "fake packer exceeded time limit"; got != want {
		t.Fatalf("stderr = %q, want %q", got, want)
	}
	mustNotExist(t, outputPath)
}
