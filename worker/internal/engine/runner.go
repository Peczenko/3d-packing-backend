package engine

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/digest"
	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

const (
	resultContentType = "model/obj"
	terminateGrace    = 5 * time.Second
	maxStderrBytes    = 8 << 10
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
				return pipeline.EngineResult{}, &pipeline.EngineFailure{Reason: describePaths(reason, request)}
			}
			return pipeline.EngineResult{}, fmt.Errorf("engine: run packer: %w", err)
		}
	}

	info, err := os.Stat(request.OutputPath)
	if err != nil {
		return pipeline.EngineResult{}, fmt.Errorf("engine: stat packer output %q: %w", request.OutputPath, err)
	}
	if !info.Mode().IsRegular() {
		return pipeline.EngineResult{}, fmt.Errorf("engine: packer output %q is not a regular file", request.OutputPath)
	}
	if info.Size() == 0 {
		return pipeline.EngineResult{}, fmt.Errorf("engine: packer output %q is empty", request.OutputPath)
	}

	checksum, err := digest.File(request.OutputPath)
	if err != nil {
		return pipeline.EngineResult{}, fmt.Errorf("engine: checksum packer output %q: %w", request.OutputPath, err)
	}

	return pipeline.EngineResult{
		FileName:    filepath.Base(request.OutputPath),
		ContentType: resultContentType,
		SizeBytes:   info.Size(),
		Checksum:    checksum,
	}, nil
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
	if stderr = strings.TrimRight(stderr, "\r\n"); stderr != "" {
		reason += ": " + stderr
	}
	return reason
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
