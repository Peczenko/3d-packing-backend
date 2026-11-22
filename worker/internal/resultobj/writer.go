package resultobj

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strconv"

	"github.com/klauspost/compress/gzip"
	"golang.org/x/sync/errgroup"

	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

const (
	FileName        = "packed.obj"
	ContentType     = "model/obj"
	ContentEncoding = "gzip"

	significantDigits = 9
	flushBytes        = 256 << 10
	fileBufferBytes   = 1 << 20
)

type Writer struct {
	Concurrency int
}

var _ pipeline.ResultWriter = Writer{}

type target struct {
	path string
	bin  int
}

type written struct {
	sizeBytes   int64
	storedBytes int64
	checksum    string
}

func (w Writer) Write(ctx context.Context, inputs []pipeline.InputFile, layout pipeline.Layout, dir string) (pipeline.ResultFiles, error) {
	if err := validate(inputs, layout); err != nil {
		return pipeline.ResultFiles{}, err
	}
	meshes, err := w.readMeshes(inputs)
	if err != nil {
		return pipeline.ResultFiles{}, err
	}

	binCount := len(layout.BinOffsets)
	targets := []target{{path: filepath.Join(dir, FileName+".gz")}}
	for number := 1; binCount > 1 && number <= binCount; number++ {
		targets = append(targets, target{path: filepath.Join(dir, fmt.Sprintf("packed-bin-%03d.obj.gz", number)), bin: number})
	}
	outputs := make([]written, len(targets))
	group, groupCtx := errgroup.WithContext(ctx)
	group.SetLimit(w.concurrency())
	for i, t := range targets {
		group.Go(func() error {
			out, err := writeTarget(groupCtx, t, meshes, layout)
			outputs[i] = out
			return err
		})
	}
	if err := group.Wait(); err != nil {
		return pipeline.ResultFiles{}, err
	}

	result := pipeline.ResultFiles{
		FileName:        FileName,
		ContentType:     ContentType,
		ContentEncoding: ContentEncoding,
		Path:            targets[0].path,
		SizeBytes:       outputs[0].sizeBytes,
		Checksum:        outputs[0].checksum,
	}
	for i, out := range outputs {
		result.StoredBytes += out.storedBytes
		if binCount == 1 || i > 0 {
			result.Bins = append(result.Bins, pipeline.ResultBin{Path: targets[i].path, SizeBytes: out.sizeBytes, Checksum: out.checksum})
		}
	}
	return result, nil
}

func (w Writer) concurrency() int {
	if w.Concurrency > 0 {
		return w.Concurrency
	}
	return runtime.GOMAXPROCS(0)
}

func validate(inputs []pipeline.InputFile, layout pipeline.Layout) error {
	if len(layout.BinOffsets) == 0 {
		return errors.New("resultobj: the layout has no bins")
	}
	if len(layout.Placements) == 0 {
		return errors.New("resultobj: the layout places nothing")
	}
	filled := make([]bool, len(layout.BinOffsets))
	for i, placement := range layout.Placements {
		if placement.Item < 0 || placement.Item >= len(inputs) {
			return fmt.Errorf("resultobj: placement %d names item %d of %d", i, placement.Item, len(inputs))
		}
		if placement.Bin < 1 || placement.Bin > len(layout.BinOffsets) {
			return fmt.Errorf("resultobj: placement %d names bin %d of %d", i, placement.Bin, len(layout.BinOffsets))
		}
		filled[placement.Bin-1] = true
	}
	for i, ok := range filled {
		if !ok {
			return fmt.Errorf("resultobj: bin %d holds no copy", i+1)
		}
	}
	return nil
}

func (w Writer) readMeshes(inputs []pipeline.InputFile) ([]*mesh, error) {
	meshes := make([]*mesh, len(inputs))
	var group errgroup.Group
	group.SetLimit(w.concurrency())
	for i, input := range inputs {
		group.Go(func() error {
			parsed, err := readInput(input.Path)
			var malformed *malformedError
			if errors.As(err, &malformed) {
				return &pipeline.EngineFailure{Reason: fmt.Sprintf("Input file '%s' could not be read back to write the result: %s", input.DisplayName, malformed)}
			}
			if err != nil {
				return fmt.Errorf("resultobj: read input %d: %w", i, err)
			}
			meshes[i] = parsed
			return nil
		})
	}
	return meshes, group.Wait()
}

func readInput(path string) (*mesh, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	return readMesh(file)
}

func writeTarget(ctx context.Context, t target, meshes []*mesh, layout pipeline.Layout) (out written, err error) {
	file, err := os.OpenFile(t.path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return written{}, fmt.Errorf("resultobj: create %s: %w", t.path, err)
	}
	defer func() {
		if closeErr := file.Close(); closeErr != nil && err == nil {
			err = fmt.Errorf("resultobj: close %s: %w", t.path, closeErr)
		}
	}()

	buffered := bufio.NewWriterSize(file, fileBufferBytes)
	compressed, err := gzip.NewWriterLevel(buffered, gzip.BestSpeed)
	if err != nil {
		return written{}, err
	}
	hash := sha256.New()
	var size int64
	buf := make([]byte, 0, flushBytes+4096)
	flush := func() error {
		hash.Write(buf)
		size += int64(len(buf))
		_, err := compressed.Write(buf)
		buf = buf[:0]
		return err
	}

	var base int64 = 1
	for index, placement := range layout.Placements {
		if t.bin != 0 && placement.Bin != t.bin {
			continue
		}
		if err := ctx.Err(); err != nil {
			return written{}, err
		}
		m := meshes[placement.Item]
		matrix := placement.Matrix
		if t.bin == 0 {
			offset := layout.BinOffsets[placement.Bin-1]
			matrix[3] += offset[0]
			matrix[7] += offset[1]
			matrix[11] += offset[2]
		}

		buf = append(buf, "o bin_"...)
		buf = strconv.AppendInt(buf, int64(placement.Bin), 10)
		buf = append(buf, "_mesh_"...)
		buf = strconv.AppendInt(buf, int64(index+1), 10)
		buf = append(buf, '\n')
		for v := 0; v < len(m.vertices); v += 3 {
			x, y, z := m.vertices[v], m.vertices[v+1], m.vertices[v+2]
			buf = append(buf, 'v', ' ')
			buf = strconv.AppendFloat(buf, matrix[0]*x+matrix[1]*y+matrix[2]*z+matrix[3], 'g', significantDigits, 64)
			buf = append(buf, ' ')
			buf = strconv.AppendFloat(buf, matrix[4]*x+matrix[5]*y+matrix[6]*z+matrix[7], 'g', significantDigits, 64)
			buf = append(buf, ' ')
			buf = strconv.AppendFloat(buf, matrix[8]*x+matrix[9]*y+matrix[10]*z+matrix[11], 'g', significantDigits, 64)
			buf = append(buf, '\n')
			if len(buf) >= flushBytes {
				if err := flush(); err != nil {
					return written{}, fmt.Errorf("resultobj: write %s: %w", t.path, err)
				}
			}
		}
		for f := 0; f < len(m.faces); f += 3 {
			buf = append(buf, 'f', ' ')
			buf = strconv.AppendInt(buf, base+int64(m.faces[f]), 10)
			buf = append(buf, ' ')
			buf = strconv.AppendInt(buf, base+int64(m.faces[f+1]), 10)
			buf = append(buf, ' ')
			buf = strconv.AppendInt(buf, base+int64(m.faces[f+2]), 10)
			buf = append(buf, '\n')
			if len(buf) >= flushBytes {
				if err := flush(); err != nil {
					return written{}, fmt.Errorf("resultobj: write %s: %w", t.path, err)
				}
			}
		}
		base += int64(len(m.vertices) / 3)
	}
	if err := flush(); err != nil {
		return written{}, fmt.Errorf("resultobj: write %s: %w", t.path, err)
	}
	if err := compressed.Close(); err != nil {
		return written{}, fmt.Errorf("resultobj: compress %s: %w", t.path, err)
	}
	if err := buffered.Flush(); err != nil {
		return written{}, fmt.Errorf("resultobj: write %s: %w", t.path, err)
	}
	info, err := file.Stat()
	if err != nil {
		return written{}, fmt.Errorf("resultobj: stat %s: %w", t.path, err)
	}
	return written{sizeBytes: size, storedBytes: info.Size(), checksum: hex.EncodeToString(hash.Sum(nil))}, nil
}
