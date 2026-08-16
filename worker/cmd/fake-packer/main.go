// Command fake-packer is a deterministic stand-in for the packing engine
// described by worker/internal/engine/runner.go. The real engine is a C++
// binary arriving later from a separate repo and team; this one implements
// the same CLI contract — --spec/--output/--time-limit-seconds, and
// --version — so the local pipeline can run end to end without it. It does
// no packing: the output merely echoes the spec it was given. Nothing in
// this repository may import this package; it is reached only as an
// executable, exactly as the real engine will be.
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"time"
)

// versionLine is what --version prints. The runner trims exactly one
// trailing newline and requires the result non-blank as engineVersion, so
// this must carry exactly one newline of its own and never be blank.
const versionLine = "fake-packer 0.1.0"

const (
	exitOK    = 0
	exitInput = 1
	exitUsage = 2
)

// sleepSpecKey and exitCodeSpecKey are the two optional top-level test keys
// that drive the worker's failure paths locally. Both are stripped from the
// echoed spec so neither ever reaches a caller as packing input.
const (
	sleepSpecKey    = "_fakeSleepSeconds"
	exitCodeSpecKey = "_fakeExitCode"
)

const timeoutMessage = "fake packer exceeded time limit"

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

func run(args []string, stdout, stderr io.Writer) int {
	if len(args) == 1 && args[0] == "--version" {
		fmt.Fprintln(stdout, versionLine)
		return exitOK
	}

	fs := flag.NewFlagSet("fake-packer", flag.ContinueOnError)
	fs.SetOutput(stderr)
	specPath := fs.String("spec", "", "path to the input spec JSON")
	outputPath := fs.String("output", "", "path to write the result JSON")
	timeLimitSeconds := fs.Int64("time-limit-seconds", 0, "wall-clock budget in seconds")
	if err := fs.Parse(args); err != nil {
		return exitUsage
	}
	if *specPath == "" || *outputPath == "" || *timeLimitSeconds <= 0 {
		fmt.Fprintln(stderr, "fake-packer: --spec, --output and a positive --time-limit-seconds are required")
		return exitUsage
	}

	raw, err := os.ReadFile(*specPath)
	if err != nil {
		fmt.Fprintln(stderr, "fake-packer: read spec:", err)
		return exitInput
	}

	spec := map[string]any{}
	if err := json.Unmarshal(raw, &spec); err != nil {
		fmt.Fprintln(stderr, "fake-packer: parse spec:", err)
		return exitInput
	}

	sleepSeconds := popFloat(spec, sleepSpecKey)
	exitCode := popInt(spec, exitCodeSpecKey)

	// The sleep simulates work but must never outrun the limit the caller
	// gave it: a real engine that hung past --time-limit-seconds would be
	// killed from outside, but this stand-in reports its own timeout so the
	// path is exercisable without depending on OS process termination.
	limit := time.Duration(*timeLimitSeconds) * time.Second
	if sleepSeconds > 0 {
		sleep := time.Duration(sleepSeconds * float64(time.Second))
		if sleep >= limit {
			time.Sleep(limit)
			fmt.Fprintln(stderr, timeoutMessage)
			return exitInput
		}
		time.Sleep(sleep)
	}

	if exitCode != 0 {
		return exitCode
	}

	if err := writeOutput(*outputPath, spec); err != nil {
		fmt.Fprintln(stderr, "fake-packer: write output:", err)
		return exitInput
	}
	return exitOK
}

func popFloat(spec map[string]any, key string) float64 {
	value, ok := spec[key]
	delete(spec, key)
	if !ok {
		return 0
	}
	number, ok := value.(float64)
	if !ok {
		return 0
	}
	return number
}

func popInt(spec map[string]any, key string) int {
	value, ok := spec[key]
	delete(spec, key)
	if !ok {
		return 0
	}
	number, ok := value.(float64)
	if !ok {
		return 0
	}
	return int(number)
}

type result struct {
	Fake bool           `json:"fake"`
	Spec map[string]any `json:"spec"`
}

// writeOutput writes compact, non-HTML-escaped JSON to a temp file next to
// the destination, syncs it, closes it, then renames it into place. The
// runner classifies exit 0 with no output file as an infrastructure error,
// which abandons the message and eventually dead-letters the job — so a
// packer that dies mid-write must never leave a partial file where the
// requested path is, and this must never return success before the rename.
func writeOutput(path string, spec map[string]any) error {
	data, err := marshalCompact(result{Fake: true, Spec: spec})
	if err != nil {
		return err
	}

	tmpPath := path + ".tmp"
	file, err := os.OpenFile(tmpPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := file.Write(data); err != nil {
		file.Close()
		os.Remove(tmpPath)
		return err
	}
	if err := file.Sync(); err != nil {
		file.Close()
		os.Remove(tmpPath)
		return err
	}
	if err := file.Close(); err != nil {
		os.Remove(tmpPath)
		return err
	}
	if err := os.Rename(tmpPath, path); err != nil {
		os.Remove(tmpPath)
		return err
	}
	return nil
}

// marshalCompact renders v the way json.Marshal would — compact, no
// trailing newline — but with HTML escaping disabled. json.Marshal always
// escapes <, > and &; the echoed spec is arbitrary input this binary has no
// business reinterpreting, so escaping it would corrupt bytes that never
// asked to be HTML-safe.
func marshalCompact(v any) ([]byte, error) {
	var buf bytes.Buffer
	encoder := json.NewEncoder(&buf)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimSuffix(buf.Bytes(), []byte("\n")), nil
}
