package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var container = []string{"--width", "10", "--length", "10", "--height", "10"}

func packerArgs(seconds, outputPath string, inputs ...string) []string {
	args := append([]string{"-t", seconds, "-o", outputPath}, container...)
	return append(args, inputs...)
}

func readManifest(t *testing.T, outputPath string) (int, []string) {
	t.Helper()
	var manifest struct {
		BinCount int `json:"bin_count"`
		Bins     []struct {
			ID  int    `json:"id"`
			Obj string `json:"obj"`
		} `json:"bins"`
	}
	if err := json.Unmarshal([]byte(readText(t, outputPath+".json")), &manifest); err != nil {
		t.Fatalf("parse manifest: %v", err)
	}
	objs := make([]string, len(manifest.Bins))
	for i, bin := range manifest.Bins {
		if bin.ID != i {
			t.Fatalf("manifest bin %d has id %d", i, bin.ID)
		}
		objs[i] = bin.Obj
	}
	return manifest.BinCount, objs
}

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
		{"missing -t", append(append([]string{"-o", "out.obj"}, container...), cube)},
		{"zero -t", packerArgs("0", "out.obj", cube)},
		{"non-numeric -t", packerArgs("soon", "out.obj", cube)},
		{"missing -o", append(append([]string{"-t", "10"}, container...), cube)},
		{"no input", packerArgs("10", "out.obj")},
		{"no container", []string{"-t", "10", "-o", "out.obj", cube}},
		{"missing --height", []string{"-t", "10", "-o", "out.obj", "--width", "1", "--length", "1", cube}},
		{"zero --width", append(packerArgs("10", "out.obj"), "--width", "0", cube)},
		{"negative --gap", append(packerArgs("10", "out.obj"), "--gap", "-1", cube)},
		{"unknown --algorithm", append(packerArgs("10", "out.obj"), "--algorithm", "greedy", cube)},
		{"--seed over 32 bits", append(packerArgs("10", "out.obj"), "--seed", "4294967296", cube)},
		{"unrecognised flag", append(packerArgs("10", "out.obj"), "-s", "7", cube)},
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
	code := run(packerArgs("10", outputPath, filepath.Join(dir, "absent.obj")), &stderr)

	if code != 1 {
		t.Fatalf("exit code = %d, want 1 (stderr: %s)", code, stderr.String())
	}
	mustNotExist(t, outputPath)
}

func TestPacksOneObjectPerInputPathAndMatchesTheGoldenFile(t *testing.T) {
	outputPath := filepath.Join(t.TempDir(), "packed.obj")

	var stderr bytes.Buffer
	if code := run(packerArgs("60", outputPath, cubePath(), cubePath()), &stderr); code != 0 {
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
	if code := run(packerArgs("5", outputPath, objWith(t, "# fake-packer: sleep=0.01")), &stderr); code != 0 {
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
	if code := run(packerArgs("10", outputPath, input), &stderr); code != 7 {
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
	if code := run(packerArgs("10", outputPath, objWith(t, "# fake-packer: sleep")), &stderr); code != 1 {
		t.Fatalf("exit code = %d, want 1 (stderr: %s)", code, stderr.String())
	}
	mustNotExist(t, outputPath)
}

func TestSleepUnderTheTimeLimitCompletesNormally(t *testing.T) {
	outputPath := filepath.Join(t.TempDir(), "packed.obj")

	started := time.Now()
	var stderr bytes.Buffer
	code := run(packerArgs("5", outputPath, objWith(t, "# fake-packer: sleep=0.2")), &stderr)
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
	code := run(packerArgs("1", outputPath, objWith(t, "# fake-packer: sleep=5")), &stderr)
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
	if code := run(packerArgs("10", outputPath, cubePath()), &stderr); code != 1 {
		t.Fatalf("exit code = %d, want 1 (stderr: %s)", code, stderr.String())
	}
	if stderr.Len() == 0 {
		t.Fatal("expected a stderr message describing the write failure")
	}
}

func TestWritesAManifestNamingTheOutputAsTheOnlyBin(t *testing.T) {
	outputPath := filepath.Join(t.TempDir(), "packed.obj")

	var stderr bytes.Buffer
	if code := run(append(packerArgs("10", outputPath), "--gap", "0.5", "--algorithm", "constructive", "--seed", "7", cubePath()), &stderr); code != 0 {
		t.Fatalf("exit code = %d, want 0 (stderr: %s)", code, stderr.String())
	}

	count, objs := readManifest(t, outputPath)
	absolute, _ := filepath.Abs(outputPath)
	if count != 1 || len(objs) != 1 || objs[0] != absolute {
		t.Fatalf("manifest = %d bins %q, want one bin naming %s", count, objs, absolute)
	}
	mustNotExist(t, filepath.Join(filepath.Dir(outputPath), "packed-bin-001.obj"))
}

func TestBinDirectivesSplitTheResultIntoOneFilePerBin(t *testing.T) {
	dir := t.TempDir()
	outputPath := filepath.Join(dir, "packed.obj")
	first := objWith(t, "# fake-packer: bin=2")
	second := objWith(t, "")

	var stderr bytes.Buffer
	if code := run(packerArgs("10", outputPath, first, second, first), &stderr); code != 0 {
		t.Fatalf("exit code = %d, want 0 (stderr: %s)", code, stderr.String())
	}

	combined := readText(t, outputPath)
	for _, name := range []string{"o bin_2_mesh_1\n", "o bin_1_mesh_2\n", "o bin_2_mesh_3\n"} {
		if !strings.Contains(combined, name) {
			t.Fatalf("combined output lacks %q:\n%s", name, combined)
		}
	}
	count, objs := readManifest(t, outputPath)
	if count != 2 || len(objs) != 2 {
		t.Fatalf("manifest = %d bins %q, want 2", count, objs)
	}
	for i, want := range []string{"o bin_1_mesh_2\nv 0 0 0\nf 1 1 1\n", "o bin_2_mesh_1\nv 0 0 0\nf 1 1 1\no bin_2_mesh_3\nv 0 0 0\nf 1 1 1\n"} {
		wantPath, _ := filepath.Abs(filepath.Join(dir, []string{"packed-bin-001.obj", "packed-bin-002.obj"}[i]))
		if objs[i] != wantPath {
			t.Fatalf("manifest bin %d = %s, want %s", i, objs[i], wantPath)
		}
		if got := readText(t, objs[i]); got != want {
			t.Fatalf("bin %d holds %q, want %q", i+1, got, want)
		}
	}
}

func TestAnEmptyBinExitsOne(t *testing.T) {
	outputPath := filepath.Join(t.TempDir(), "packed.obj")

	var stderr bytes.Buffer
	if code := run(packerArgs("10", outputPath, objWith(t, "# fake-packer: bin=2")), &stderr); code != 1 {
		t.Fatalf("exit code = %d, want 1 (stderr: %s)", code, stderr.String())
	}
	mustNotExist(t, outputPath)
}
