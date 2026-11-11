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
	resultContentType = "model/obj"
	manifestSuffix    = ".json"
	terminateGrace    = 5 * time.Second
	maxStderrBytes    = 8 << 10
	killedReason      = "packer was killed, most likely for exceeding the worker's memory limit"
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

func (r *Runner) Run(ctx context.Context, request pipeline.RunRequest) (pipeline.EngineResult, error) {
	if request.Runtime <= 0 {
		return pipeline.EngineResult{}, fmt.Errorf("engine: runtime limit must be positive, got %s", request.Runtime)
	}
	if len(request.Inputs) == 0 {
		return pipeline.EngineResult{}, errNoInputs
	}
	executable, err := r.resolve()
	if err != nil {
		return pipeline.EngineResult{}, err
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
			return pipeline.EngineResult{}, fmt.Errorf("engine: packing canceled: %w", ctx.Err())
		case errors.Is(runCtx.Err(), context.DeadlineExceeded):
			return pipeline.EngineResult{}, &pipeline.EngineFailure{Reason: timeoutReason(seconds)}
		default:
			var exitErr *exec.ExitError
			if errors.As(err, &exitErr) {
				reason := exitReason(exitErr.ExitCode(), exitErr.ProcessState.String(), stderr.String())
				if killedBySystem(exitErr.ProcessState) {
					reason = withStderr(killedReason, stderr.String())
				}
				return pipeline.EngineResult{}, &pipeline.EngineFailure{Reason: describePaths(reason, request)}
			}
			return pipeline.EngineResult{}, fmt.Errorf("engine: run packer: %w", err)
		}
	}

	combined, err := describeOutput(request.OutputPath)
	if err != nil {
		return pipeline.EngineResult{}, err
	}
	bins, err := readBins(request.OutputPath, combined)
	if err != nil {
		return pipeline.EngineResult{}, err
	}

	return pipeline.EngineResult{
		FileName:    filepath.Base(request.OutputPath),
		ContentType: resultContentType,
		SizeBytes:   combined.SizeBytes,
		Checksum:    combined.Checksum,
		Bins:        bins,
	}, nil
}

func describeOutput(path string) (pipeline.ResultBin, error) {
	info, err := os.Stat(path)
	if err != nil {
		return pipeline.ResultBin{}, fmt.Errorf("engine: stat packer output %q: %w", path, err)
	}
	if !info.Mode().IsRegular() {
		return pipeline.ResultBin{}, fmt.Errorf("engine: packer output %q is not a regular file", path)
	}
	if info.Size() == 0 {
		return pipeline.ResultBin{}, fmt.Errorf("engine: packer output %q is empty", path)
	}
	checksum, err := digest.File(path)
	if err != nil {
		return pipeline.ResultBin{}, fmt.Errorf("engine: checksum packer output %q: %w", path, err)
	}
	return pipeline.ResultBin{Path: path, SizeBytes: info.Size(), Checksum: checksum}, nil
}

func readBins(outputPath string, combined pipeline.ResultBin) ([]pipeline.ResultBin, error) {
	manifestPath := outputPath + manifestSuffix
	data, err := os.ReadFile(manifestPath)
	if err != nil {
		return nil, fmt.Errorf("engine: read packer manifest: %w", err)
	}
	var manifest struct {
		BinCount int `json:"bin_count"`
		Bins     []struct {
			ID  int     `json:"id"`
			Obj *string `json:"obj"`
		} `json:"bins"`
	}
	if err := json.Unmarshal(data, &manifest); err != nil {
		return nil, fmt.Errorf("engine: parse packer manifest %q: %w", manifestPath, err)
	}
	if manifest.BinCount < 1 || len(manifest.Bins) != manifest.BinCount {
		return nil, fmt.Errorf("engine: packer manifest lists %d bins for a bin count of %d", len(manifest.Bins), manifest.BinCount)
	}
	if manifest.BinCount == 1 {
		return []pipeline.ResultBin{combined}, nil
	}

	workspace, err := filepath.Abs(filepath.Dir(outputPath))
	if err != nil {
		return nil, fmt.Errorf("engine: resolve packer workspace: %w", err)
	}
	bins := make([]pipeline.ResultBin, 0, manifest.BinCount)
	for i, entry := range manifest.Bins {
		if entry.ID != i || entry.Obj == nil {
			return nil, fmt.Errorf("engine: packer manifest entry %d does not name bin %d's OBJ", i, i)
		}
		path := filepath.Clean(*entry.Obj)
		if filepath.Dir(path) != workspace {
			return nil, fmt.Errorf("engine: packer wrote bin %d outside its workspace: %q", i, path)
		}
		bin, err := describeOutput(path)
		if err != nil {
			return nil, err
		}
		bins = append(bins, bin)
	}
	return bins, nil
}

func arguments(seconds int64, request pipeline.RunRequest) []string {
	args := []string{"-t", strconv.FormatInt(seconds, 10), "-o", request.OutputPath}
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
	return strings.ReplaceAll(reason, request.OutputPath, filepath.Base(request.OutputPath))
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
