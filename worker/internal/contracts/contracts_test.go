package contracts

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

const fixtureDir = "../../../contracts/packing/v1"

func readFixture(t *testing.T, name string) []byte {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(fixtureDir, name))
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

func TestDispatchMessageRoundTrip(t *testing.T) {
	fixture := readFixture(t, "dispatch-message.example.json")
	want := compact(t, fixture)

	msg, err := DecodeDispatch(fixture)
	if err != nil {
		t.Fatalf("DecodeDispatch: %v", err)
	}
	got, err := json.Marshal(msg)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("round trip mismatch:\n got:  %s\n want: %s", got, want)
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

func TestStartedEventRoundTrip(t *testing.T) {
	fixture := readFixture(t, "started-event.example.json")
	want := compact(t, fixture)

	var event WorkerEvent
	if err := json.Unmarshal(fixture, &event); err != nil {
		t.Fatalf("json.Unmarshal: %v", err)
	}
	got, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("round trip mismatch:\n got:  %s\n want: %s", got, want)
	}

	encoded, err := EncodeStarted(event.JobID, event.EngineVersion, event.EngineChecksum)
	if err != nil {
		t.Fatalf("EncodeStarted: %v", err)
	}
	if !bytes.Equal(encoded, want) {
		t.Fatalf("EncodeStarted mismatch:\n got:  %s\n want: %s", encoded, want)
	}
}

func TestSucceededEventRoundTrip(t *testing.T) {
	fixture := readFixture(t, "succeeded-event.example.json")
	want := compact(t, fixture)

	var event WorkerEvent
	if err := json.Unmarshal(fixture, &event); err != nil {
		t.Fatalf("json.Unmarshal: %v", err)
	}
	got, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("round trip mismatch:\n got:  %s\n want: %s", got, want)
	}

	encoded, err := EncodeSucceeded(
		event.JobID,
		event.EngineVersion,
		event.EngineChecksum,
		event.ResultFileName,
		event.ResultContentType,
		event.ResultSizeBytes,
		event.ResultChecksum,
	)
	if err != nil {
		t.Fatalf("EncodeSucceeded: %v", err)
	}
	if !bytes.Equal(encoded, want) {
		t.Fatalf("EncodeSucceeded mismatch:\n got:  %s\n want: %s", encoded, want)
	}
}

func TestFailedEventRoundTrip(t *testing.T) {
	fixture := readFixture(t, "failed-event.example.json")
	want := compact(t, fixture)

	var event WorkerEvent
	if err := json.Unmarshal(fixture, &event); err != nil {
		t.Fatalf("json.Unmarshal: %v", err)
	}
	got, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("round trip mismatch:\n got:  %s\n want: %s", got, want)
	}

	encoded, err := EncodeFailed(event.JobID, event.EngineVersion, event.EngineChecksum, event.Reason)
	if err != nil {
		t.Fatalf("EncodeFailed: %v", err)
	}
	if !bytes.Equal(encoded, want) {
		t.Fatalf("EncodeFailed mismatch:\n got:  %s\n want: %s", encoded, want)
	}
}

func TestDecodeDispatchRejectsUnsupportedVersion(t *testing.T) {
	_, err := DecodeDispatch([]byte(`{"messageVersion":2,"jobId":"00000000-0000-0000-0000-000000000001"}`))
	if !errors.Is(err, ErrUnsupportedVersion) {
		t.Fatalf("expected ErrUnsupportedVersion, got %v", err)
	}
}

func TestDecodeDispatchRejectsInvalidUUID(t *testing.T) {
	_, err := DecodeDispatch([]byte(`{"messageVersion":1,"jobId":"not-a-uuid"}`))
	if !errors.Is(err, ErrInvalidJobID) {
		t.Fatalf("expected ErrInvalidJobID, got %v", err)
	}
}

func TestDecodeDispatchRejectsMissingJobID(t *testing.T) {
	_, err := DecodeDispatch([]byte(`{"messageVersion":1}`))
	if !errors.Is(err, ErrInvalidJobID) {
		t.Fatalf("expected ErrInvalidJobID, got %v", err)
	}
}

func TestDecodeDispatchRejectsMalformedJSON(t *testing.T) {
	_, err := DecodeDispatch([]byte(`{not json`))
	if !errors.Is(err, ErrInvalidJSON) {
		t.Fatalf("expected ErrInvalidJSON, got %v", err)
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
