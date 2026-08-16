package engine

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

const (
	resultContentType = "application/octet-stream"
	terminateGrace    = 5 * time.Second
	maxStderrBytes    = 8 << 10
	maxVersionBytes   = 8 << 10
)

var errBlankVersion = errors.New("engine: packer --version printed nothing")

type Runner struct {
	path string

	resolveOnce sync.Once
	executable  string
	resolveErr  error
}

func NewRunner(path string) *Runner {
	return &Runner{path: path}
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

func (r *Runner) Provenance(ctx context.Context) (pipeline.EngineProvenance, error) {
	executable, err := r.resolve()
	if err != nil {
		return pipeline.EngineProvenance{}, err
	}

	cmd := exec.CommandContext(ctx, executable, "--version")
	var stdout headBuffer
	var stderr tailBuffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := cmd.Run(); err != nil {
		if noise := stderr.String(); noise != "" {
			return pipeline.EngineProvenance{}, fmt.Errorf("engine: %s --version: %w: %s", executable, err, noise)
		}
		return pipeline.EngineProvenance{}, fmt.Errorf("engine: %s --version: %w", executable, err)
	}

	version := strings.TrimSuffix(stdout.String(), "\n")
	if strings.TrimSpace(version) == "" {
		return pipeline.EngineProvenance{}, fmt.Errorf("engine: %s: %w", executable, errBlankVersion)
	}

	checksum, err := checksumFile(executable)
	if err != nil {
		return pipeline.EngineProvenance{}, fmt.Errorf("engine: checksum packer %s: %w", executable, err)
	}

	return pipeline.EngineProvenance{Version: version, Checksum: checksum}, nil
}

func (r *Runner) Run(ctx context.Context, request pipeline.RunRequest) (pipeline.EngineResult, error) {
	if request.Runtime <= 0 {
		return pipeline.EngineResult{}, fmt.Errorf("engine: runtime limit must be positive, got %s", request.Runtime)
	}
	executable, err := r.resolve()
	if err != nil {
		return pipeline.EngineResult{}, err
	}
	seconds := limitSeconds(request.Runtime)

	runCtx, cancel := context.WithTimeout(ctx, request.Runtime)
	defer cancel()

	cmd := exec.CommandContext(runCtx, executable,
		"--spec", request.SpecPath,
		"--output", request.OutputPath,
		"--time-limit-seconds", strconv.FormatInt(seconds, 10),
	)
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
				return pipeline.EngineResult{}, &pipeline.EngineFailure{
					Reason: exitReason(exitErr.ExitCode(), exitErr.ProcessState.String(), stderr.String()),
				}
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

	checksum, err := checksumFile(request.OutputPath)
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

func checksumFile(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()

	digest := sha256.New()
	if _, err := io.Copy(digest, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
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

type headBuffer struct {
	data []byte
}

func (b *headBuffer) Write(p []byte) (int, error) {
	if room := maxVersionBytes - len(b.data); room > 0 {
		if room > len(p) {
			room = len(p)
		}
		b.data = append(b.data, p[:room]...)
	}
	return len(p), nil
}

func (b *headBuffer) String() string {
	return string(b.data)
}
