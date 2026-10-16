package contracts

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strconv"
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

func TestDecodeRequestRejectsUnsupportedVersion(t *testing.T) {
	_, err := DecodeRequest([]byte(`{"requestVersion":2,"maxRuntimeSeconds":60,"spec":{"a":1}}`))
	if !errors.Is(err, ErrUnsupportedVersion) {
		t.Fatalf("expected ErrUnsupportedVersion, got %v", err)
	}
}

func TestDecodeRequestRejectsRuntimeAboveRange(t *testing.T) {
	_, err := DecodeRequest([]byte(`{"requestVersion":1,"maxRuntimeSeconds":7201,"spec":{"a":1}}`))
	if !errors.Is(err, ErrInvalidRuntime) {
		t.Fatalf("expected ErrInvalidRuntime, got %v", err)
	}
}

func TestDecodeRequestRejectsRuntimeBelowRange(t *testing.T) {
	_, err := DecodeRequest([]byte(`{"requestVersion":1,"maxRuntimeSeconds":0,"spec":{"a":1}}`))
	if !errors.Is(err, ErrInvalidRuntime) {
		t.Fatalf("expected ErrInvalidRuntime, got %v", err)
	}
}

func TestDecodeRequestRejectsEmptySpec(t *testing.T) {
	_, err := DecodeRequest([]byte(`{"requestVersion":1,"maxRuntimeSeconds":60}`))
	if !errors.Is(err, ErrInvalidSpec) {
		t.Fatalf("expected ErrInvalidSpec, got %v", err)
	}
}

func TestDecodeRequestRejectsNullSpec(t *testing.T) {
	_, err := DecodeRequest([]byte(`{"requestVersion":1,"maxRuntimeSeconds":60,"spec":null}`))
	if !errors.Is(err, ErrInvalidSpec) {
		t.Fatalf("expected ErrInvalidSpec, got %v", err)
	}
}

func TestDecodeRequestNeverInspectsSpecKeys(t *testing.T) {
	fixture := []byte(`{"requestVersion":1,"maxRuntimeSeconds":60,"spec":{"anything":"the packer decides","nested":{"x":[1,2,3]}}}`)
	env, err := DecodeRequest(fixture)
	if err != nil {
		t.Fatalf("DecodeRequest: %v", err)
	}
	var raw json.RawMessage
	if err := json.Unmarshal(fixture, &struct {
		Spec *json.RawMessage `json:"spec"`
	}{Spec: &raw}); err != nil {
		t.Fatalf("json.Unmarshal: %v", err)
	}
	if !bytes.Equal(env.Spec, raw) {
		t.Fatalf("spec bytes mutated:\n got:  %s\n want: %s", env.Spec, raw)
	}
}
