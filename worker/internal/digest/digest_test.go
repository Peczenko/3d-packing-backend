package digest

import (
	"os"
	"path/filepath"
	"testing"
)

func TestFileHashesTheBytesAsLowercaseHex(t *testing.T) {
	path := filepath.Join(t.TempDir(), "abc.txt")
	if err := os.WriteFile(path, []byte("abc"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	got, err := File(path)
	if err != nil {
		t.Fatalf("File: %v", err)
	}
	if want := "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"; got != want {
		t.Fatalf("File = %s, want %s", got, want)
	}
}

func TestFileReportsAMissingFile(t *testing.T) {
	if _, err := File(filepath.Join(t.TempDir(), "absent")); err == nil {
		t.Fatal("File succeeded for a missing file")
	}
}
