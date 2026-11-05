package engine

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

func TestRunReportsAnExternalKillAsLikelyOutOfMemory(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("SIGKILL is a POSIX signal")
	}
	dir := t.TempDir()
	packer := filepath.Join(dir, "packer")
	if err := os.WriteFile(packer, []byte("#!/bin/sh\nkill -9 $$\n"), 0o755); err != nil {
		t.Fatalf("write fake packer: %v", err)
	}

	_, err := NewRunner(packer, "v0.0.3", time.Second).Run(context.Background(), pipeline.RunRequest{
		Inputs:     []pipeline.InputFile{{Path: filepath.Join(dir, "0.obj"), DisplayName: "cube.obj", Copies: 1}},
		OutputPath: filepath.Join(dir, "packed.obj"),
		Runtime:    5 * time.Second,
	})

	var failure *pipeline.EngineFailure
	if !errors.As(err, &failure) {
		t.Fatalf("expected an EngineFailure, got %v", err)
	}
	if !strings.Contains(failure.Reason, "memory limit") {
		t.Fatalf("reason %q does not point at the memory limit", failure.Reason)
	}
}
