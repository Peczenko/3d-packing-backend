//go:build windows

package engine

import "os/exec"

// terminate kills outright, because Windows offers nothing gentler:
// os.Process.Signal rejects every signal except os.Kill, so sending
// syscall.SIGTERM there would only ever return an error and leave the
// packer running until WaitDelay elapsed. Windows is the development
// platform only; terminate_unix.go carries the production semantics.
func terminate(cmd *exec.Cmd) error {
	return cmd.Process.Kill()
}
