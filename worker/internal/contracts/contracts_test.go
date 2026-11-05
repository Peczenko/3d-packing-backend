package contracts

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

const contractsDir = "../../../contracts/packing"

func readFixture(t *testing.T, name string) []byte {
	t.Helper()
	return readVersionedFixture(t, RequestVersion, name)
}

func readVersionedFixture(t *testing.T, version int, name string) []byte {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(contractsDir, "v"+strconv.Itoa(version), name))
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	return data
}

func compact(t *testing.T, data []byte) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := json.Compact(&buf, data); err != nil {
		t.Fatalf("compact fixture json: %v", err)
	}
	return buf.Bytes()
}

func assertActivityRoundTrip[T any](t *testing.T, name string) T {
	t.Helper()
	fixture := readVersionedFixture(t, ActivityVersion, name)
	var decoded T
	decoder := json.NewDecoder(bytes.NewReader(fixture))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&decoded); err != nil {
		t.Fatalf("decode %s: %v", name, err)
	}
	got, err := json.Marshal(decoded)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}
	if want := compact(t, fixture); !bytes.Equal(got, want) {
		t.Fatalf("round trip mismatch for %s:\n got:  %s\n want: %s", name, got, want)
	}
	return decoded
}

func TestRunEngineInputRoundTrip(t *testing.T) {
	input := assertActivityRoundTrip[RunEngineInput](t, "run-engine-input.example.json")
	if input.ContractVersion != ActivityVersion {
		t.Fatalf("example carries contract version %d, want %d", input.ContractVersion, ActivityVersion)
	}
}

func TestEngineStartedSignalRoundTrip(t *testing.T) {
	assertActivityRoundTrip[EngineStarted](t, "engine-started-signal.example.json")
}

func TestEngineResultRoundTrip(t *testing.T) {
	assertActivityRoundTrip[EngineResult](t, "run-engine-result.example.json")
}

func TestEngineFailureDetailsRoundTrip(t *testing.T) {
	assertActivityRoundTrip[EngineFailureDetails](t, "engine-failure-details.example.json")
}

func TestValidateRunEngineInputNormalisesJobIDToLowercase(t *testing.T) {
	input, err := ValidateRunEngineInput(RunEngineInput{ContractVersion: ActivityVersion, JobID: "3F2504E0-4F89-41D3-9A0C-0305E82C3301"})
	if err != nil {
		t.Fatalf("ValidateRunEngineInput: %v", err)
	}
	if input.JobID != "3f2504e0-4f89-41d3-9a0c-0305e82c3301" {
		t.Fatalf("jobId = %q, want lowercase", input.JobID)
	}
}

func TestValidateRunEngineInputRejectsAnotherContractVersion(t *testing.T) {
	for _, version := range []int{0, 1, ActivityVersion + 1} {
		_, err := ValidateRunEngineInput(RunEngineInput{ContractVersion: version, JobID: "3f2504e0-4f89-41d3-9a0c-0305e82c3301"})
		if !errors.Is(err, ErrUnsupportedVersion) {
			t.Fatalf("version %d: expected ErrUnsupportedVersion, got %v", version, err)
		}
	}
}

func TestValidateRunEngineInputRejectsANonCanonicalJobID(t *testing.T) {
	for _, jobID := range []string{"", "not-a-uuid", "3f2504e04f8941d39a0c0305e82c3301", "{3f2504e0-4f89-41d3-9a0c-0305e82c3301}"} {
		_, err := ValidateRunEngineInput(RunEngineInput{ContractVersion: ActivityVersion, JobID: jobID})
		if !errors.Is(err, ErrInvalidJobID) {
			t.Fatalf("jobId %q: expected ErrInvalidJobID, got %v", jobID, err)
		}
	}
}

func TestRequestEnvelopeRoundTrip(t *testing.T) {
	fixture := readFixture(t, "request-envelope.example.json")
	want := compact(t, fixture)

	env, err := DecodeRequest(fixture)
	if err != nil {
		t.Fatalf("DecodeRequest: %v", err)
	}
	got, err := json.Marshal(env)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("round trip mismatch:\n got:  %s\n want: %s", got, want)
	}
}

type wireRequest struct {
	RequestVersion     int             `json:"requestVersion"`
	MaxRuntimeSeconds  int64           `json:"maxRuntimeSeconds"`
	InputSchemaVersion int             `json:"inputSchemaVersion"`
	Items              []RequestItem   `json:"items"`
	Parameters         json.RawMessage `json:"parameters"`
}

func validItem() RequestItem {
	return RequestItem{
		StorageKey: "files/0b6c3c1e-4c4f-4a39-9b5e-2b8c2e6b1f00",
		FileName:   "bunny.obj",
		Format:     "OBJ",
		Checksum:   strings.Repeat("c", 64),
		Copies:     3,
	}
}

func encodeRequest(t *testing.T, mutate func(*wireRequest)) []byte {
	t.Helper()
	request := wireRequest{
		RequestVersion:     RequestVersion,
		MaxRuntimeSeconds:  120,
		InputSchemaVersion: 1,
		Items:              []RequestItem{validItem()},
		Parameters:         json.RawMessage(`{}`),
	}
	mutate(&request)
	data, err := json.Marshal(request)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}
	return data
}

func items(count, copies int) []RequestItem {
	out := make([]RequestItem, count)
	for i := range out {
		out[i] = validItem()
		out[i].Copies = copies
	}
	return out
}

func TestDecodeRequestAcceptsTheExampleAndKeepsItsItems(t *testing.T) {
	envelope, err := DecodeRequest(encodeRequest(t, func(*wireRequest) {}))
	if err != nil {
		t.Fatalf("DecodeRequest: %v", err)
	}
	if len(envelope.Items) != 1 || envelope.Items[0] != validItem() {
		t.Fatalf("items = %+v, want the one valid item", envelope.Items)
	}
	if len(envelope.ParameterArgs) != 0 {
		t.Fatalf("parameter args = %q, want none for empty parameters", envelope.ParameterArgs)
	}
}

func TestDecodeRequestRejectsEnvelopesOutsideTheContract(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(*wireRequest)
		want   error
	}{
		{"another request version", func(r *wireRequest) { r.RequestVersion = 2 }, ErrUnsupportedVersion},
		{"runtime below range", func(r *wireRequest) { r.MaxRuntimeSeconds = 0 }, ErrInvalidRuntime},
		{"runtime above range", func(r *wireRequest) { r.MaxRuntimeSeconds = 7201 }, ErrInvalidRuntime},
		{"schema version zero", func(r *wireRequest) { r.InputSchemaVersion = 0 }, ErrInvalidSchemaVersion},
		{"no items", func(r *wireRequest) { r.Items = nil }, ErrInvalidItems},
		{"fifty-one items", func(r *wireRequest) { r.Items = items(51, 1) }, ErrInvalidItems},
		{"zero copies", func(r *wireRequest) { r.Items[0].Copies = 0 }, ErrInvalidItems},
		{"a hundred and one copies", func(r *wireRequest) { r.Items[0].Copies = 101 }, ErrInvalidItems},
		{"two hundred and one objects", func(r *wireRequest) { r.Items = items(3, 67) }, ErrInvalidItems},
		{"storage key outside files/", func(r *wireRequest) { r.Items[0].StorageKey = "packing-jobs/x" }, ErrInvalidItems},
		{"storage key escaping files/", func(r *wireRequest) { r.Items[0].StorageKey = "files/../packing-jobs/x" }, ErrInvalidItems},
		{"blank file name", func(r *wireRequest) { r.Items[0].FileName = " " }, ErrInvalidItems},
		{"checksum not sha-256", func(r *wireRequest) { r.Items[0].Checksum = "abc" }, ErrInvalidItems},
		{"format the engine cannot read", func(r *wireRequest) { r.Items[0].Format = "STL" }, ErrUnsupportedFormat},
		{"parameters not an object", func(r *wireRequest) { r.Parameters = json.RawMessage(`[]`) }, ErrInvalidParameters},
		{"missing parameters", func(r *wireRequest) { r.Parameters = nil }, ErrInvalidParameters},
		{"unknown parameter", func(r *wireRequest) { r.Parameters = json.RawMessage(`{"seed":7}`) }, ErrInvalidParameters},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := DecodeRequest(encodeRequest(t, tc.mutate))
			if !errors.Is(err, tc.want) {
				t.Fatalf("expected %v, got %v", tc.want, err)
			}
		})
	}
}

func TestDecodeRequestRejectsInvalidJSON(t *testing.T) {
	if _, err := DecodeRequest([]byte(`{not json`)); !errors.Is(err, ErrInvalidJSON) {
		t.Fatalf("expected ErrInvalidJSON, got %v", err)
	}
}
