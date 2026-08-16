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

const versionLine = "fake-packer 0.1.0"

const (
	exitOK    = 0
	exitInput = 1
	exitUsage = 2
)

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

func marshalCompact(v any) ([]byte, error) {
	var buf bytes.Buffer
	encoder := json.NewEncoder(&buf)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimSuffix(buf.Bytes(), []byte("\n")), nil
}
