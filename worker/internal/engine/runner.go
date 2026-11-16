package engine

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/digest"
	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

const (
	transformsFileName = "transforms.txt"
	manifestFileName   = "packed.json"
	terminateGrace     = 5 * time.Second
	maxStderrBytes     = 8 << 10
	killedReason       = "packer was killed, most likely for exceeding the worker's memory limit"
)

var (
	errBlankVersion = errors.New("engine: the packer version is blank")
	errNoInputs     = errors.New("engine: a packing run needs at least one input")
)

type Runner struct {
	path    string
	version string
	grace   time.Duration

	resolveOnce sync.Once
	executable  string
	resolveErr  error
}

func NewRunner(path, version string, grace time.Duration) *Runner {
	return &Runner{path: path, version: version, grace: grace}
}

var _ pipeline.Engine = (*Runner)(nil)

func (r *Runner) resolve() (string, error) {
	r.resolveOnce.Do(func() {
		r.executable, r.resolveErr = exec.LookPath(r.path)
	})
	if r.resolveErr != nil {
		return "", fmt.Errorf("engine: locate packer %q: %w", r.path, r.resolveErr)
	}
	return r.executable, nil
}

func (r *Runner) Provenance(context.Context) (pipeline.EngineProvenance, error) {
	if strings.TrimSpace(r.version) == "" {
		return pipeline.EngineProvenance{}, errBlankVersion
	}
	executable, err := r.resolve()
	if err != nil {
		return pipeline.EngineProvenance{}, err
	}
	checksum, err := digest.File(executable)
	if err != nil {
		return pipeline.EngineProvenance{}, fmt.Errorf("engine: checksum packer %s: %w", executable, err)
	}
	return pipeline.EngineProvenance{Version: r.version, Checksum: checksum}, nil
}

func (r *Runner) Run(ctx context.Context, request pipeline.RunRequest) (pipeline.Packing, error) {
	if request.Runtime <= 0 {
		return pipeline.Packing{}, fmt.Errorf("engine: runtime limit must be positive, got %s", request.Runtime)
	}
	if len(request.Inputs) == 0 {
		return pipeline.Packing{}, errNoInputs
	}
	executable, err := r.resolve()
	if err != nil {
		return pipeline.Packing{}, err
	}
	seconds := limitSeconds(request.Runtime)

	runCtx, cancel := context.WithTimeout(ctx, request.Runtime+r.grace)
	defer cancel()

	cmd := exec.CommandContext(runCtx, executable, arguments(seconds, request)...)
	var stderr tailBuffer
	cmd.Stderr = &stderr
	cmd.Cancel = func() error { return terminate(cmd) }
	cmd.WaitDelay = terminateGrace

	if err := cmd.Run(); err != nil {
		switch {
		case ctx.Err() != nil:
			return pipeline.Packing{}, fmt.Errorf("engine: packing canceled: %w", ctx.Err())
		case errors.Is(runCtx.Err(), context.DeadlineExceeded):
			return pipeline.Packing{}, &pipeline.EngineFailure{Reason: timeoutReason(seconds)}
		default:
			var exitErr *exec.ExitError
			if errors.As(err, &exitErr) {
				reason := exitReason(exitErr.ExitCode(), exitErr.ProcessState.String(), stderr.String())
				if killedBySystem(exitErr.ProcessState) {
					reason = withStderr(killedReason, stderr.String())
				}
				return pipeline.Packing{}, &pipeline.EngineFailure{Reason: describePaths(reason, request)}
			}
			return pipeline.Packing{}, fmt.Errorf("engine: run packer: %w", err)
		}
	}

	manifest, err := readManifest(manifestPath(request))
	if err != nil {
		return pipeline.Packing{}, err
	}
	layout, err := readLayout(manifest, request.Inputs)
	if err != nil {
		return pipeline.Packing{}, err
	}
	return pipeline.Packing{
		Layout:             layout,
		PreparationSeconds: manifest.PreparationSeconds,
		SearchSeconds:      manifest.SearchSeconds,
	}, nil
}

type packerManifest struct {
	BinCount int `json:"bin_count"`
	Bins     []struct {
		ID            int       `json:"id"`
		DisplayOffset []float64 `json:"display_offset"`
	} `json:"bins"`
	Placements []struct {
		InputIndex int       `json:"input_index"`
		Bin        int       `json:"bin"`
		Matrix     []float64 `json:"matrix"`
	} `json:"placements"`
	PreparationSeconds float64 `json:"preparation_seconds"`
	SearchSeconds      float64 `json:"search_seconds"`
}

func manifestPath(request pipeline.RunRequest) string {
	return filepath.Join(request.WorkDir, manifestFileName)
}

func readManifest(path string) (packerManifest, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return packerManifest{}, fmt.Errorf("engine: read packer manifest: %w", err)
	}
	var manifest packerManifest
	if err := json.Unmarshal(data, &manifest); err != nil {
		return packerManifest{}, fmt.Errorf("engine: parse packer manifest %q: %w", path, err)
	}
	if manifest.BinCount < 1 || len(manifest.Bins) != manifest.BinCount {
		return packerManifest{}, fmt.Errorf("engine: packer manifest lists %d bins for a bin count of %d", len(manifest.Bins), manifest.BinCount)
	}
	return manifest, nil
}

func readLayout(manifest packerManifest, inputs []pipeline.InputFile) (pipeline.Layout, error) {
	items := make([]int, 0, len(manifest.Placements))
	for item, input := range inputs {
		for range input.Copies {
			items = append(items, item)
		}
	}
	if len(manifest.Placements) != len(items) {
		return pipeline.Layout{}, fmt.Errorf("engine: packer manifest places %d copies of the %d requested", len(manifest.Placements), len(items))
	}
	layout := pipeline.Layout{
		BinOffsets: make([][3]float64, 0, manifest.BinCount),
		Placements: make([]pipeline.Placement, 0, len(items)),
	}
	for i, bin := range manifest.Bins {
		if bin.ID != i || len(bin.DisplayOffset) != 3 {
			return pipeline.Layout{}, fmt.Errorf("engine: packer manifest entry %d does not describe bin %d's display offset", i, i)
		}
		layout.BinOffsets = append(layout.BinOffsets, [3]float64(bin.DisplayOffset))
	}
	for i, placement := range manifest.Placements {
		if placement.InputIndex != i || placement.Bin < 0 || placement.Bin >= manifest.BinCount || len(placement.Matrix) != 12 {
			return pipeline.Layout{}, fmt.Errorf("engine: packer manifest placement %d is not copy %d placed in one of %d bins by a 3x4 matrix", i, i, manifest.BinCount)
		}
		layout.Placements = append(layout.Placements, pipeline.Placement{
			Item:   items[i],
			Bin:    placement.Bin + 1,
			Matrix: [12]float64(placement.Matrix),
		})
	}
	return layout, nil
}

func arguments(seconds int64, request pipeline.RunRequest) []string {
	args := []string{
		"-t", strconv.FormatInt(seconds, 10),
		"-m", filepath.Join(request.WorkDir, transformsFileName),
		"--manifest", manifestPath(request),
	}
	args = append(args, request.ParameterArgs...)
	for _, input := range request.Inputs {
		for range input.Copies {
			args = append(args, input.Path)
		}
	}
	return args
}

func describePaths(reason string, request pipeline.RunRequest) string {
	inputs := slices.Clone(request.Inputs)
	slices.SortFunc(inputs, func(a, b pipeline.InputFile) int { return len(b.Path) - len(a.Path) })
	for _, input := range inputs {
		reason = strings.ReplaceAll(reason, input.Path, input.DisplayName)
	}
	if request.WorkDir == "" {
		return reason
	}
	return strings.ReplaceAll(reason, request.WorkDir+string(filepath.Separator), "")
}

func limitSeconds(runtime time.Duration) int64 {
	return int64((runtime + time.Second - 1) / time.Second)
}

func timeoutReason(seconds int64) string {
	return fmt.Sprintf("packing runtime limit of %ds exceeded", seconds)
}

func exitReason(code int, state string, stderr string) string {
	reason := fmt.Sprintf("packer exited with code %d", code)
	if code < 0 {
		reason = "packer did not exit normally: " + state
	}
	return withStderr(reason, stderr)
}

func withStderr(reason, stderr string) string {
	if stderr = strings.TrimRight(stderr, "\r\n"); stderr != "" {
		reason += ": " + stderr
	}
	return reason
}

func killedBySystem(state *os.ProcessState) bool {
	status, ok := state.Sys().(syscall.WaitStatus)
	return ok && status.Signaled() && status.Signal() == syscall.SIGKILL
}

type tailBuffer struct {
	data []byte
}

func (b *tailBuffer) Write(p []byte) (int, error) {
	written := len(p)
	if len(p) >= maxStderrBytes {
		b.data = append(b.data[:0], p[len(p)-maxStderrBytes:]...)
		return written, nil
	}
	if overflow := len(b.data) + len(p) - maxStderrBytes; overflow > 0 {
		b.data = append(b.data[:0], b.data[overflow:]...)
	}
	b.data = append(b.data, p...)
	return written, nil
}

func (b *tailBuffer) String() string {
	return string(b.data)
}
