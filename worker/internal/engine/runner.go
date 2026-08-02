// Package engine runs the external packer executable. The packer is a
// replaceable binary owned elsewhere, so this package knows only its CLI
// contract — `--version`, and `--spec/--output/--time-limit-seconds` — and
// nothing about the spec it reads or the result it writes.
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
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

const (
	// resultContentType is fixed: the result's format is the packer's
	// business, and the worker only ever moves the bytes.
	resultContentType = "application/octet-stream"

	// terminateGrace is how long a canceled packer gets to shut down
	// cleanly before os/exec kills it.
	terminateGrace = 5 * time.Second

	// maxStderrBytes bounds what a failure reason carries; a packer that
	// logs progress to stderr can otherwise produce megabytes of it.
	maxStderrBytes = 8 << 10
)

var errBlankVersion = errors.New("engine: packer --version printed nothing")

type Runner struct {
	path string
}

func NewRunner(path string) *Runner {
	return &Runner{path: path}
}

var _ pipeline.Engine = (*Runner)(nil)

func (r *Runner) Provenance(ctx context.Context) (pipeline.EngineProvenance, error) {
	// Resolve once so the bytes that are hashed are the bytes that are
	// executed; hashing r.path directly would disagree with exec's own
	// lookup for a bare name.
	executable, err := exec.LookPath(r.path)
	if err != nil {
		return pipeline.EngineProvenance{}, fmt.Errorf("engine: locate packer %q: %w", r.path, err)
	}

	printed, err := exec.CommandContext(ctx, executable, "--version").Output()
	if err != nil {
		var exitErr *exec.ExitError
		if errors.As(err, &exitErr) && len(exitErr.Stderr) > 0 {
			return pipeline.EngineProvenance{}, fmt.Errorf("engine: %s --version: %w: %s", executable, err, tail(exitErr.Stderr))
		}
		return pipeline.EngineProvenance{}, fmt.Errorf("engine: %s --version: %w", executable, err)
	}

	version := strings.TrimSuffix(string(printed), "\n")
	if strings.TrimSpace(version) == "" {
		// The backend rejects a blank engineVersion outright, so every
		// lifecycle event for this job would fail to decode. Failing here
		// abandons the delivery instead of running a packer whose result
		// could never be recorded.
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
	seconds := limitSeconds(request.Runtime)

	runCtx, cancel := context.WithTimeout(ctx, request.Runtime)
	defer cancel()

	cmd := exec.CommandContext(runCtx, r.path,
		"--spec", request.SpecPath,
		"--output", request.OutputPath,
		"--time-limit-seconds", strconv.FormatInt(seconds, 10),
	)
	var stderr tailBuffer
	cmd.Stderr = &stderr
	// Ask first, kill second: the packer must get a chance to exit cleanly,
	// and WaitDelay bounds how long that chance lasts. See terminate_unix.go
	// and terminate_windows.go for what "ask" means on each platform.
	cmd.Cancel = func() error { return terminate(cmd) }
	cmd.WaitDelay = terminateGrace

	if err := cmd.Run(); err != nil {
		switch {
		case ctx.Err() != nil:
			// The caller withdrew the job (lost lock, shutdown). That is the
			// worker's problem, not the packing job's, so it must not be an
			// EngineFailure: the delivery has to be abandoned, not failed.
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

// limitSeconds rounds up, so a sub-second runtime never reaches the packer
// as `--time-limit-seconds 0` — a usage error the packer would reject and
// the worker would then misreport as a genuine packing failure.
func limitSeconds(runtime time.Duration) int64 {
	return int64((runtime + time.Second - 1) / time.Second)
}

// timeoutReason formats the limit in seconds explicitly. Duration.String()
// renders 60s as "1m0s" and 7200s as "2h0m0s", neither of which is the
// figure the job was given.
func timeoutReason(seconds int64) string {
	return fmt.Sprintf("packing runtime limit of %ds exceeded", seconds)
}

// exitReason describes a packer that did not exit 0. A packer killed by a
// signal — the OOM killer being the realistic case for a packing engine —
// has no exit code, only -1, so the process state names the signal instead.
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

// tailBuffer retains only the last maxStderrBytes written to it.
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

func tail(b []byte) string {
	if len(b) > maxStderrBytes {
		b = b[len(b)-maxStderrBytes:]
	}
	return string(b)
}
