package main

import (
	"bytes"
	"flag"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	exitOK    = 0
	exitInput = 1
	exitUsage = 2
)

const (
	directivePrefix = "# fake-packer:"
	timeoutMessage  = "fake packer exceeded time limit"
)

type directives struct {
	sleep    time.Duration
	exitCode int
}

func main() {
	os.Exit(run(os.Args[1:], os.Stderr))
}

func run(args []string, stderr io.Writer) int {
	fs := flag.NewFlagSet("fake-packer", flag.ContinueOnError)
	fs.SetOutput(stderr)
	seconds := fs.Int64("t", 0, "annealing time in seconds")
	outputPath := fs.String("o", "", "path of the packed OBJ")
	if err := fs.Parse(args); err != nil {
		return exitUsage
	}
	inputs := fs.Args()
	if *seconds <= 0 || *outputPath == "" || len(inputs) == 0 {
		fmt.Fprintln(stderr, "fake-packer: -t <seconds>, -o <output.obj> and at least one input OBJ are required")
		return exitUsage
	}

	var packed bytes.Buffer
	var injected directives
	for i, path := range inputs {
		data, err := os.ReadFile(path)
		if err != nil {
			fmt.Fprintf(stderr, "fake-packer: read %s: %v\n", path, err)
			return exitInput
		}
		fmt.Fprintf(&packed, "o Object_%d\n", i+1)
		if err := copyGeometry(&packed, data, &injected); err != nil {
			fmt.Fprintf(stderr, "fake-packer: %s: %v\n", path, err)
			return exitInput
		}
	}

	limit := time.Duration(*seconds) * time.Second
	if injected.sleep > 0 {
		if injected.sleep >= limit {
			time.Sleep(limit)
			fmt.Fprintln(stderr, timeoutMessage)
			return exitInput
		}
		time.Sleep(injected.sleep)
	}
	if injected.exitCode != 0 {
		fmt.Fprintf(stderr, "fake-packer: injected failure while packing %s\n", strings.Join(inputs, " "))
		return injected.exitCode
	}

	if err := writeAtomically(*outputPath, packed.Bytes()); err != nil {
		fmt.Fprintln(stderr, "fake-packer: write output:", err)
		return exitInput
	}
	return exitOK
}

func copyGeometry(out *bytes.Buffer, data []byte, injected *directives) error {
	for _, line := range strings.Split(string(data), "\n") {
		trimmed := strings.TrimSpace(line)
		switch {
		case trimmed == "":
		case strings.HasPrefix(trimmed, directivePrefix):
			if err := injected.parse(strings.TrimSpace(strings.TrimPrefix(trimmed, directivePrefix))); err != nil {
				return err
			}
		case strings.HasPrefix(trimmed, "o "):
		default:
			out.WriteString(trimmed)
			out.WriteByte('\n')
		}
	}
	return nil
}

func (d *directives) parse(directive string) error {
	key, value, ok := strings.Cut(directive, "=")
	if !ok {
		return fmt.Errorf("directive %q is not key=value", directive)
	}
	switch key {
	case "sleep":
		seconds, err := strconv.ParseFloat(value, 64)
		if err != nil || seconds < 0 {
			return fmt.Errorf("sleep %q is not a non-negative number of seconds", value)
		}
		d.sleep = max(d.sleep, time.Duration(seconds*float64(time.Second)))
	case "exit":
		code, err := strconv.Atoi(value)
		if err != nil {
			return fmt.Errorf("exit %q is not an integer", value)
		}
		d.exitCode = code
	default:
		return fmt.Errorf("unknown directive %q", key)
	}
	return nil
}

func writeAtomically(path string, data []byte) error {
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
