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

func readText(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return strings.ReplaceAll(string(data), "\r\n", "\n")
}

func mustNotExist(t *testing.T, path string) {
	t.Helper()
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("expected %s not to exist, stat error: %v", path, err)
	}
}

func cubePath() string {
	return filepath.Join("..", "..", "testdata", "cube.obj")
}

func objWith(t *testing.T, directive string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "part.obj")
	writeFile(t, path, "v 0 0 0\n"+directive+"\nf 1 1 1\n")
	return path
}

func TestMissingOrInvalidArgumentsExitTwo(t *testing.T) {
	cube := cubePath()
	for _, tc := range []struct {
		name string
		args []string
	}{
		{"no arguments at all", nil},
		{"missing -t", []string{"-o", "out.obj", cube}},
		{"zero -t", []string{"-t", "0", "-o", "out.obj", cube}},
		{"non-numeric -t", []string{"-t", "soon", "-o", "out.obj", cube}},
		{"missing -o", []string{"-t", "10", cube}},
		{"no input", []string{"-t", "10", "-o", "out.obj"}},
		{"unrecognised flag", []string{"-t", "10", "-o", "out.obj", "-s", "7", cube}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			outputPath := filepath.Join(t.TempDir(), "out.obj")
			args := make([]string, len(tc.args))
			for i, a := range tc.args {
				if a == "out.obj" {
					a = outputPath
				}
				args[i] = a
			}

			var stderr bytes.Buffer
			if code := run(args, &stderr); code != 2 {
				t.Fatalf("exit code = %d, want 2 (stderr: %s)", code, stderr.String())
			}
			mustNotExist(t, outputPath)
		})
	}
}

func TestUnreadableInputExitsOne(t *testing.T) {
	dir := t.TempDir()
	outputPath := filepath.Join(dir, "out.obj")

	var stderr bytes.Buffer
	code := run([]string{"-t", "10", "-o", outputPath, filepath.Join(dir, "absent.obj")}, &stderr)

	if code != 1 {
		t.Fatalf("exit code = %d, want 1 (stderr: %s)", code, stderr.String())
	}
	mustNotExist(t, outputPath)
}

func TestPacksOneObjectPerInputPathAndMatchesTheGoldenFile(t *testing.T) {
	outputPath := filepath.Join(t.TempDir(), "packed.obj")

	var stderr bytes.Buffer
	if code := run([]string{"-t", "60", "-o", outputPath, cubePath(), cubePath()}, &stderr); code != 0 {
		t.Fatalf("exit code = %d, want 0 (stderr: %s)", code, stderr.String())
	}

	got := readText(t, outputPath)
	want := readText(t, filepath.Join("..", "..", "testdata", "expected-packed.obj"))
	if got != want {
		t.Fatalf("packed output does not match the golden file:\n got:\n%s\n want:\n%s", got, want)
	}
	mustNotExist(t, outputPath+".tmp")
}

func TestDirectivesAreObeyedAndNeverCopied(t *testing.T) {
	outputPath := filepath.Join(t.TempDir(), "packed.obj")

	var stderr bytes.Buffer
	if code := run([]string{"-t", "5", "-o", outputPath, objWith(t, "# fake-packer: sleep=0.01")}, &stderr); code != 0 {
		t.Fatalf("exit code = %d, want 0 (stderr: %s)", code, stderr.String())
	}
	if strings.Contains(readText(t, outputPath), "fake-packer") {
		t.Fatal("a directive was copied into the packed output")
	}
}

func TestExitDirectiveFailsWithoutOutputAndNamesTheInputs(t *testing.T) {
	outputPath := filepath.Join(t.TempDir(), "packed.obj")
	input := objWith(t, "# fake-packer: exit=7")

	var stderr bytes.Buffer
	if code := run([]string{"-t", "10", "-o", outputPath, input}, &stderr); code != 7 {
		t.Fatalf("exit code = %d, want 7", code)
	}
	mustNotExist(t, outputPath)
	if !strings.Contains(stderr.String(), input) {
		t.Fatalf("stderr %q does not name the input %s", stderr.String(), input)
	}
}

func TestMalformedDirectiveExitsOne(t *testing.T) {
	outputPath := filepath.Join(t.TempDir(), "packed.obj")

	var stderr bytes.Buffer
	if code := run([]string{"-t", "10", "-o", outputPath, objWith(t, "# fake-packer: sleep")}, &stderr); code != 1 {
		t.Fatalf("exit code = %d, want 1 (stderr: %s)", code, stderr.String())
	}
	mustNotExist(t, outputPath)
}

func TestSleepUnderTheTimeLimitCompletesNormally(t *testing.T) {
	outputPath := filepath.Join(t.TempDir(), "packed.obj")

	started := time.Now()
	var stderr bytes.Buffer
	code := run([]string{"-t", "5", "-o", outputPath, objWith(t, "# fake-packer: sleep=0.2")}, &stderr)
	elapsed := time.Since(started)

	if code != 0 {
		t.Fatalf("exit code = %d, want 0 (stderr: %s)", code, stderr.String())
	}
	if elapsed < 200*time.Millisecond {
		t.Fatalf("run returned after %s, want at least 200ms (the sleep was skipped)", elapsed)
	}
}

func TestSleepAtOrOverTheTimeLimitTimesOut(t *testing.T) {
	outputPath := filepath.Join(t.TempDir(), "packed.obj")

	started := time.Now()
	var stderr bytes.Buffer
	code := run([]string{"-t", "1", "-o", outputPath, objWith(t, "# fake-packer: sleep=5")}, &stderr)
	elapsed := time.Since(started)

	if code != 1 {
		t.Fatalf("exit code = %d, want 1 (stderr: %s)", code, stderr.String())
	}
	if elapsed >= 3*time.Second {
		t.Fatalf("run took %s, want well under the 5s sleep request: the time limit was not enforced", elapsed)
	}
	if elapsed < 900*time.Millisecond {
		t.Fatalf("run took only %s, want close to the 1s limit", elapsed)
	}
	if got, want := strings.TrimRight(stderr.String(), "\n"), "fake packer exceeded time limit"; got != want {
		t.Fatalf("stderr = %q, want %q", got, want)
	}
	mustNotExist(t, outputPath)
}

func TestOutputWriteFailureExitsOne(t *testing.T) {
	outputPath := filepath.Join(t.TempDir(), "missing-parent", "packed.obj")

	var stderr bytes.Buffer
	if code := run([]string{"-t", "10", "-o", outputPath, cubePath()}, &stderr); code != 1 {
		t.Fatalf("exit code = %d, want 1 (stderr: %s)", code, stderr.String())
	}
	if stderr.Len() == 0 {
		t.Fatal("expected a stderr message describing the write failure")
	}
}
