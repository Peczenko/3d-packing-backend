package contracts

import (
	"encoding/json"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strconv"
	"testing"
)

const contractsRoot = "../../../contracts/packing"

var versionDirPattern = regexp.MustCompile(`^v(\d+)$`)

func discoverContractVersions(t *testing.T) []int {
	t.Helper()
	entries, err := os.ReadDir(contractsRoot)
	if err != nil {
		t.Fatalf("read contracts root %s: %v", contractsRoot, err)
	}
	var versions []int
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		m := versionDirPattern.FindStringSubmatch(entry.Name())
		if m == nil {
			continue
		}
		v, err := strconv.Atoi(m[1])
		if err != nil {
			t.Fatalf("parse version directory %q: %v", entry.Name(), err)
		}
		versions = append(versions, v)
	}
	if len(versions) == 0 {
		t.Fatalf("no version directories found under %s", contractsRoot)
	}
	sort.Ints(versions)
	return versions
}

func acceptsVersion(version int) bool {
	dispatch := []byte(fmt.Sprintf(`{"messageVersion":%d,"jobId":"00000000-0000-0000-0000-000000000001"}`, version))
	_, err := DecodeDispatch(dispatch)
	return err == nil
}

func emittedVersion(t *testing.T) int {
	t.Helper()
	encoded, err := EncodeStarted("00000000-0000-0000-0000-000000000001", "packer 0.3.0", "a")
	if err != nil {
		t.Fatalf("EncodeStarted: %v", err)
	}
	var wire struct {
		MessageVersion int `json:"messageVersion"`
	}
	if err := json.Unmarshal(encoded, &wire); err != nil {
		t.Fatalf("json.Unmarshal encoded started event: %v", err)
	}
	return wire.MessageVersion
}

func equalInts(a, b []int) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestDecodeAcceptsExactlyTheDeclaredContractVersions(t *testing.T) {
	declared := discoverContractVersions(t)

	var accepted []int
	for _, v := range declared {
		if acceptsVersion(v) {
			accepted = append(accepted, v)
		}
	}
	if !equalInts(accepted, declared) {
		t.Fatalf("DecodeDispatch accepts %v, but contracts/packing declares %v", accepted, declared)
	}

	beyondNewest := declared[len(declared)-1] + 1
	if acceptsVersion(beyondNewest) {
		t.Fatalf("DecodeDispatch accepted version %d, which has no contracts/packing/v%d directory", beyondNewest, beyondNewest)
	}
}

func TestEmittedVersionIsAcceptedAndIsTheNewestDeclaredContractVersion(t *testing.T) {
	declared := discoverContractVersions(t)
	newest := declared[len(declared)-1]
	emitted := emittedVersion(t)

	if emitted != newest {
		t.Fatalf("this package emits messageVersion %d, want newest declared contract version %d", emitted, newest)
	}
	if !acceptsVersion(emitted) {
		t.Fatalf("DecodeDispatch does not accept the version this package emits (%d)", emitted)
	}
}
