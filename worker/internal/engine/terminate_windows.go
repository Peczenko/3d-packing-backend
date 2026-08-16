//go:build windows

package engine

import "os/exec"

func terminate(cmd *exec.Cmd) error {
	return cmd.Process.Kill()
}
