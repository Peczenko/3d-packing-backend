package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
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
	bin      int
}

type object struct {
	bin      int
	geometry []byte
}

type manifestBin struct {
	ID  int    `json:"id"`
	Obj string `json:"obj"`
}

func main() {
	os.Exit(run(os.Args[1:], os.Stderr))
}

func run(args []string, stderr io.Writer) int {
	fs := flag.NewFlagSet("fake-packer", flag.ContinueOnError)
	fs.SetOutput(stderr)
	seconds := fs.Int64("t", 0, "time budget in seconds")
	outputPath := fs.String("o", "", "path of the packed OBJ")
	width := fs.Float64("width", 0, "container width (X)")
	length := fs.Float64("length", 0, "container length (Y)")
	height := fs.Float64("height", 0, "container height (Z)")
	gap := fs.Float64("gap", 0, "minimum surface clearance")
	algorithm := fs.String("algorithm", "anneal", "anneal or constructive")
	seed := fs.Uint64("seed", 42, "random seed")
	if err := fs.Parse(args); err != nil {
		return exitUsage
	}
	inputs := fs.Args()
	if *seconds <= 0 || *outputPath == "" || len(inputs) == 0 {
		fmt.Fprintln(stderr, "fake-packer: -t <seconds>, -o <output.obj> and at least one input OBJ are required")
		return exitUsage
	}
	if !(*width > 0) || !(*length > 0) || !(*height > 0) {
		fmt.Fprintln(stderr, "fake-packer: specify positive --width, --height and --length")
		return exitUsage
	}
	if *gap < 0 || (*algorithm != "anneal" && *algorithm != "constructive") || *seed > math.MaxUint32 {
		fmt.Fprintln(stderr, "fake-packer: --gap must be non-negative, --algorithm anneal or constructive, --seed a 32-bit integer")
		return exitUsage
	}

	var injected directives
	objects := make([]object, 0, len(inputs))
	binCount := 1
	for _, path := range inputs {
		data, err := os.ReadFile(path)
		if err != nil {
			fmt.Fprintf(stderr, "fake-packer: read %s: %v\n", path, err)
			return exitInput
		}
		injected.bin = 0
		var geometry bytes.Buffer
		if err := copyGeometry(&geometry, data, &injected); err != nil {
			fmt.Fprintf(stderr, "fake-packer: %s: %v\n", path, err)
			return exitInput
		}
		bin := max(injected.bin, 1)
		binCount = max(binCount, bin)
		objects = append(objects, object{bin: bin, geometry: geometry.Bytes()})
	}
	for bin := 1; bin <= binCount; bin++ {
		if len(pack(objects, bin)) == 0 {
			fmt.Fprintf(stderr, "fake-packer: bin %d holds no input\n", bin)
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

	if err := writeResult(*outputPath, objects, binCount); err != nil {
		fmt.Fprintln(stderr, "fake-packer: write output:", err)
		return exitInput
	}
	return exitOK
}

func writeResult(outputPath string, objects []object, binCount int) error {
	if err := writeAtomically(outputPath, pack(objects, 0)); err != nil {
		return err
	}
	bins := make([]manifestBin, binCount)
	for i := range bins {
		path := outputPath
		if binCount > 1 {
			path = binPath(outputPath, i+1)
			if err := writeAtomically(path, pack(objects, i+1)); err != nil {
				return err
			}
		}
		absolute, err := filepath.Abs(path)
		if err != nil {
			return err
		}
		bins[i] = manifestBin{ID: i, Obj: absolute}
	}
	manifest, err := json.Marshal(struct {
		SchemaVersion int           `json:"schema_version"`
		BinCount      int           `json:"bin_count"`
		Bins          []manifestBin `json:"bins"`
	}{1, binCount, bins})
	if err != nil {
		return err
	}
	return writeAtomically(outputPath+".json", manifest)
}

func pack(objects []object, onlyBin int) []byte {
	var packed bytes.Buffer
	for i, obj := range objects {
		if onlyBin != 0 && obj.bin != onlyBin {
			continue
		}
		fmt.Fprintf(&packed, "o bin_%d_mesh_%d\n", obj.bin, i+1)
		packed.Write(obj.geometry)
	}
	return packed.Bytes()
}

func binPath(outputPath string, bin int) string {
	return strings.TrimSuffix(outputPath, filepath.Ext(outputPath)) + fmt.Sprintf("-bin-%03d.obj", bin)
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
	case "bin":
		bin, err := strconv.Atoi(value)
		if err != nil || bin < 1 {
			return fmt.Errorf("bin %q is not a positive integer", value)
		}
		d.bin = bin
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
