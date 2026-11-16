package resultobj

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/klauspost/compress/gzip"

	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

const fixture = "testdata/packer-v0.0.5"

type fixtureManifest struct {
	Bins []struct {
		DisplayOffset [3]float64 `json:"display_offset"`
	} `json:"bins"`
	Placements []struct {
		Input  string      `json:"input"`
		Bin    int         `json:"bin"`
		Matrix [12]float64 `json:"matrix"`
	} `json:"placements"`
}

func packerFixture(t *testing.T) ([]pipeline.InputFile, pipeline.Layout) {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(fixture, "packed.json"))
	if err != nil {
		t.Fatalf("read manifest: %v", err)
	}
	var manifest fixtureManifest
	if err := json.Unmarshal(data, &manifest); err != nil {
		t.Fatalf("parse manifest: %v", err)
	}
	var inputs []pipeline.InputFile
	var layout pipeline.Layout
	for _, bin := range manifest.Bins {
		layout.BinOffsets = append(layout.BinOffsets, bin.DisplayOffset)
	}
	for _, placement := range manifest.Placements {
		if len(inputs) == 0 || inputs[len(inputs)-1].DisplayName != placement.Input {
			inputs = append(inputs, pipeline.InputFile{Path: filepath.Join(fixture, placement.Input), DisplayName: placement.Input})
		}
		inputs[len(inputs)-1].Copies++
		layout.Placements = append(layout.Placements, pipeline.Placement{Item: len(inputs) - 1, Bin: placement.Bin + 1, Matrix: placement.Matrix})
	}
	return inputs, layout
}

func decompress(t *testing.T, path string) []byte {
	t.Helper()
	file, err := os.Open(path)
	if err != nil {
		t.Fatalf("open %s: %v", path, err)
	}
	defer file.Close()
	reader, err := gzip.NewReader(file)
	if err != nil {
		t.Fatalf("%s is not gzip: %v", path, err)
	}
	content, err := io.ReadAll(reader)
	if err != nil {
		t.Fatalf("decompress %s: %v", path, err)
	}
	return content
}

func digest(content []byte) string {
	sum := sha256.Sum256(content)
	return hex.EncodeToString(sum[:])
}

func assertSameGeometry(t *testing.T, name string, got, want []byte) {
	t.Helper()
	gotLines := strings.Split(strings.TrimRight(string(got), "\n"), "\n")
	wantLines := strings.Split(strings.TrimRight(string(want), "\n"), "\n")
	if len(gotLines) != len(wantLines) {
		t.Fatalf("%s has %d lines, packer wrote %d", name, len(gotLines), len(wantLines))
	}
	for i := range wantLines {
		if !strings.HasPrefix(wantLines[i], "v ") {
			if gotLines[i] != wantLines[i] {
				t.Fatalf("%s line %d = %q, packer wrote %q", name, i+1, gotLines[i], wantLines[i])
			}
			continue
		}
		gotFields, wantFields := strings.Fields(gotLines[i]), strings.Fields(wantLines[i])
		if len(gotFields) != 4 || gotFields[0] != "v" {
			t.Fatalf("%s line %d = %q, want a vertex", name, i+1, gotLines[i])
		}
		for axis := 1; axis <= 3; axis++ {
			g, _ := strconv.ParseFloat(gotFields[axis], 64)
			w, _ := strconv.ParseFloat(wantFields[axis], 64)
			if math.Abs(g-w) > 1e-9+1e-8*math.Abs(w) {
				t.Fatalf("%s line %d = %q, packer wrote %q", name, i+1, gotLines[i], wantLines[i])
			}
		}
	}
}

func TestWriteReproducesPackersOwnOutputFromItsManifest(t *testing.T) {
	inputs, layout := packerFixture(t)
	dir := t.TempDir()

	result, err := Writer{}.Write(context.Background(), inputs, layout, dir)
	if err != nil {
		t.Fatalf("Write: %v", err)
	}

	combined := decompress(t, result.Path)
	want, _ := os.ReadFile(filepath.Join(fixture, "packed.obj"))
	assertSameGeometry(t, "packed.obj", combined, want)
	if result.SizeBytes != int64(len(combined)) || result.Checksum != digest(combined) {
		t.Fatalf("combined result reports %d bytes / %s, its content is %d bytes / %s", result.SizeBytes, result.Checksum, len(combined), digest(combined))
	}
	if len(result.Bins) != 2 {
		t.Fatalf("bins = %+v, want 2", result.Bins)
	}
	var stored int64
	if info, err := os.Stat(result.Path); err == nil {
		stored += info.Size()
	}
	for i, bin := range result.Bins {
		content := decompress(t, bin.Path)
		name := "packed-bin-00" + strconv.Itoa(i+1) + ".obj"
		want, _ := os.ReadFile(filepath.Join(fixture, name))
		assertSameGeometry(t, name, content, want)
		if bin.SizeBytes != int64(len(content)) || bin.Checksum != digest(content) {
			t.Fatalf("bin %d reports %d bytes / %s, its content is %d bytes / %s", i+1, bin.SizeBytes, bin.Checksum, len(content), digest(content))
		}
		if info, err := os.Stat(bin.Path); err == nil {
			stored += info.Size()
		}
	}
	if result.StoredBytes != stored {
		t.Fatalf("StoredBytes = %d, the files hold %d", result.StoredBytes, stored)
	}
	if result.FileName != "packed.obj" || result.ContentType != "model/obj" || result.ContentEncoding != "gzip" {
		t.Fatalf("result describes itself as %q / %q / %q", result.FileName, result.ContentType, result.ContentEncoding)
	}
	if result.Layout == nil || len(result.Layout.Placements) != len(layout.Placements) {
		t.Fatalf("result layout = %+v, want the layout it was written from", result.Layout)
	}
}

func TestWriteWritesEachFileWhateverTheConcurrency(t *testing.T) {
	inputs, layout := packerFixture(t)
	sequential, err := Writer{Concurrency: 1}.Write(context.Background(), inputs, layout, t.TempDir())
	if err != nil {
		t.Fatalf("Write: %v", err)
	}
	parallel, err := Writer{Concurrency: 8}.Write(context.Background(), inputs, layout, t.TempDir())
	if err != nil {
		t.Fatalf("Write: %v", err)
	}
	if sequential.Checksum != parallel.Checksum {
		t.Fatalf("combined checksum differs: %s vs %s", sequential.Checksum, parallel.Checksum)
	}
	for i := range sequential.Bins {
		if sequential.Bins[i].Checksum != parallel.Bins[i].Checksum {
			t.Fatalf("bin %d checksum differs", i+1)
		}
	}
}

func TestWriteDescribesASingleContainerByTheCombinedFile(t *testing.T) {
	dir := t.TempDir()
	input := filepath.Join(dir, "0.obj")
	if err := os.WriteFile(input, []byte("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	layout := pipeline.Layout{
		BinOffsets: [][3]float64{{0, 0, 0}},
		Placements: []pipeline.Placement{
			{Item: 0, Bin: 1, Matrix: [12]float64{1, 0, 0, 10, 0, 1, 0, 0, 0, 0, 1, 0}},
			{Item: 0, Bin: 1, Matrix: [12]float64{0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0.5}},
		},
	}

	result, err := Writer{}.Write(context.Background(), []pipeline.InputFile{{Path: input, DisplayName: "tri.obj", Copies: 2}}, layout, dir)
	if err != nil {
		t.Fatalf("Write: %v", err)
	}

	want := "o bin_1_mesh_1\nv 10 0 0\nv 11 0 0\nv 10 1 0\nf 1 2 3\n" +
		"o bin_1_mesh_2\nv 0 0 0.5\nv 0 1 0.5\nv -1 0 0.5\nf 4 5 6\n"
	if got := string(decompress(t, result.Path)); got != want {
		t.Fatalf("result =\n%s\nwant\n%s", got, want)
	}
	only := pipeline.ResultBin{Path: result.Path, SizeBytes: result.SizeBytes, Checksum: result.Checksum}
	if len(result.Bins) != 1 || result.Bins[0] != only {
		t.Fatalf("bins = %+v, want the combined file %+v", result.Bins, only)
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 2 {
		t.Fatalf("dir holds %d entries, want the input and one result file", len(entries))
	}
}

func TestWriteFormatsCoordinatesToNineSignificantDigits(t *testing.T) {
	dir := t.TempDir()
	input := filepath.Join(dir, "0.obj")
	if err := os.WriteFile(input, []byte("v 0.1 1234.5678912345 0.0000123456789\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	layout := pipeline.Layout{
		BinOffsets: [][3]float64{{0, 0, 0}},
		Placements: []pipeline.Placement{{Item: 0, Bin: 1, Matrix: [12]float64{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0}}},
	}

	result, err := Writer{}.Write(context.Background(), []pipeline.InputFile{{Path: input, DisplayName: "tri.obj", Copies: 1}}, layout, dir)
	if err != nil {
		t.Fatalf("Write: %v", err)
	}

	first := strings.Split(string(decompress(t, result.Path)), "\n")[1]
	if first != "v 0.1 1234.56789 1.23456789e-05" {
		t.Fatalf("first vertex = %q", first)
	}
}

func TestWriteReportsAnUnreadableInputAsAnEngineFailureNamingIt(t *testing.T) {
	dir := t.TempDir()
	input := filepath.Join(dir, "0.obj")
	if err := os.WriteFile(input, []byte("v 0 0 0\nv 1 0 zero\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	layout := pipeline.Layout{
		BinOffsets: [][3]float64{{0, 0, 0}},
		Placements: []pipeline.Placement{{Item: 0, Bin: 1, Matrix: [12]float64{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0}}},
	}

	_, err := Writer{}.Write(context.Background(), []pipeline.InputFile{{Path: input, DisplayName: "bracket.obj", Copies: 1}}, layout, dir)

	var failure *pipeline.EngineFailure
	if !errors.As(err, &failure) {
		t.Fatalf("Write error = %v, want an engine failure", err)
	}
	if want := `Input file 'bracket.obj' could not be read back to write the result: line 2: "zero" is not a coordinate`; failure.Reason != want {
		t.Fatalf("reason = %q, want %q", failure.Reason, want)
	}
}

func TestWriteInfrastructureProblemsAreNotEngineFailures(t *testing.T) {
	layout := pipeline.Layout{
		BinOffsets: [][3]float64{{0, 0, 0}},
		Placements: []pipeline.Placement{{Item: 0, Bin: 1, Matrix: [12]float64{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0}}},
	}
	dir := t.TempDir()
	input := filepath.Join(dir, "0.obj")
	if err := os.WriteFile(input, []byte("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	for _, tc := range []struct {
		name   string
		inputs []pipeline.InputFile
		layout pipeline.Layout
		dir    string
	}{
		{"a missing input", []pipeline.InputFile{{Path: filepath.Join(dir, "absent.obj"), DisplayName: "a.obj", Copies: 1}}, layout, dir},
		{"an unwritable directory", []pipeline.InputFile{{Path: input, DisplayName: "a.obj", Copies: 1}}, layout, filepath.Join(dir, "absent")},
		{"a placement in a bin the layout lacks", []pipeline.InputFile{{Path: input, DisplayName: "a.obj", Copies: 1}},
			pipeline.Layout{BinOffsets: layout.BinOffsets, Placements: []pipeline.Placement{{Item: 0, Bin: 2}}}, dir},
		{"a placement of an item the request lacks", []pipeline.InputFile{{Path: input, DisplayName: "a.obj", Copies: 1}},
			pipeline.Layout{BinOffsets: layout.BinOffsets, Placements: []pipeline.Placement{{Item: 1, Bin: 1}}}, dir},
		{"an empty bin", []pipeline.InputFile{{Path: input, DisplayName: "a.obj", Copies: 1}},
			pipeline.Layout{BinOffsets: [][3]float64{{0, 0, 0}, {5, 0, 0}}, Placements: []pipeline.Placement{{Item: 0, Bin: 1}}}, dir},
		{"no placements", []pipeline.InputFile{{Path: input, DisplayName: "a.obj", Copies: 1}}, pipeline.Layout{BinOffsets: layout.BinOffsets}, dir},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Writer{}.Write(context.Background(), tc.inputs, tc.layout, tc.dir)
			var failure *pipeline.EngineFailure
			if err == nil || errors.As(err, &failure) {
				t.Fatalf("Write error = %v, want an infrastructure error", err)
			}
		})
	}
}

func TestWriteStopsWhenCanceled(t *testing.T) {
	inputs, layout := packerFixture(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := Writer{}.Write(ctx, inputs, layout, t.TempDir())
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("Write error = %v, want context.Canceled", err)
	}
}

func BenchmarkWrite(b *testing.B) {
	dir := b.TempDir()
	input := filepath.Join(dir, "grid.obj")
	var model bytes.Buffer
	out := bufio.NewWriter(&model)
	const side = 300
	for y := 0; y <= side; y++ {
		for x := 0; x <= side; x++ {
			out.WriteString("v " + strconv.FormatFloat(float64(x)*1.37, 'f', 6, 32) + " " + strconv.FormatFloat(float64(y)*0.91, 'f', 6, 32) + " " + strconv.FormatFloat(math.Sin(float64(x*y)), 'f', 6, 32) + "\n")
		}
	}
	for y := 0; y < side; y++ {
		for x := 0; x < side; x++ {
			a := y*(side+1) + x + 1
			out.WriteString("f " + strconv.Itoa(a) + " " + strconv.Itoa(a+1) + " " + strconv.Itoa(a+side+2) + "\n")
			out.WriteString("f " + strconv.Itoa(a) + " " + strconv.Itoa(a+side+2) + " " + strconv.Itoa(a+side+1) + "\n")
		}
	}
	out.Flush()
	if err := os.WriteFile(input, model.Bytes(), 0o600); err != nil {
		b.Fatal(err)
	}
	layout := pipeline.Layout{BinOffsets: [][3]float64{{0, 0, 0}, {500, 0, 0}}}
	for i := range 10 {
		angle := float64(i) * 0.3
		c, s := math.Cos(angle), math.Sin(angle)
		layout.Placements = append(layout.Placements, pipeline.Placement{Item: 0, Bin: i%2 + 1, Matrix: [12]float64{c, -s, 0, 10, s, c, 0, 20, 0, 0, 1, 30}})
	}
	inputs := []pipeline.InputFile{{Path: input, DisplayName: "grid.obj", Copies: 10}}
	b.ResetTimer()
	for range b.N {
		result, err := Writer{}.Write(context.Background(), inputs, layout, b.TempDir())
		if err != nil {
			b.Fatal(err)
		}
		b.SetBytes(2 * result.SizeBytes)
	}
}
