//go:build unix

package engine

import (
	"os/exec"
	"syscall"
)

func terminate(cmd *exec.Cmd) error {
	return cmd.Process.Signal(syscall.SIGTERM)
}
