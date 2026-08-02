//go:build unix

package engine

import (
	"os/exec"
	"syscall"
)

// terminate asks the packer to stop. This is the production path: the
// packer runs on Linux and gets SIGTERM, so it can finish writing and exit
// on its own; cmd.WaitDelay turns the request into a kill if it does not.
func terminate(cmd *exec.Cmd) error {
	return cmd.Process.Signal(syscall.SIGTERM)
}
