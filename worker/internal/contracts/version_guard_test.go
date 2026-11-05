package contracts

import (
	"os"
	"regexp"
	"sort"
	"strconv"
	"testing"
)

var versionDirPattern = regexp.MustCompile(`^v(\d+)$`)

func discoverVersions(t *testing.T, root string) []int {
	t.Helper()
	entries, err := os.ReadDir(root)
	if err != nil {
		t.Fatalf("read contracts root %s: %v", root, err)
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
		t.Fatalf("no version directories found under %s", root)
	}
	sort.Ints(versions)
	return versions
}

func acceptsActivityVersion(version int) bool {
	_, err := ValidateRunEngineInput(RunEngineInput{ContractVersion: version, JobID: "00000000-0000-0000-0000-000000000001"})
	return err == nil
}

func TestActivityContractIsTheNewestDeclaredVersionAndTheOnlyOneAccepted(t *testing.T) {
	declared := discoverVersions(t, contractsDir)
	newest := declared[len(declared)-1]

	if ActivityVersion != newest {
		t.Fatalf("worker speaks activity contract v%d, newest declared contract is v%d", ActivityVersion, newest)
	}
	for _, v := range declared {
		if got, want := acceptsActivityVersion(v), v == newest; got != want {
			t.Fatalf("activity input v%d accepted=%t, want %t", v, got, want)
		}
	}
	if acceptsActivityVersion(newest + 1) {
		t.Fatalf("activity input v%d accepted, but contracts/packing/v%d does not exist", newest+1, newest+1)
	}
}

func TestRequestEnvelopeVersionIsDeclared(t *testing.T) {
	if _, err := os.Stat(contractsDir + "/v" + strconv.Itoa(RequestVersion) + "/request-envelope.example.json"); err != nil {
		t.Fatalf("request envelope v%d has no declared example: %v", RequestVersion, err)
	}
}
