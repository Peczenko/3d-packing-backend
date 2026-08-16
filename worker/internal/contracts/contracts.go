// Package contracts implements the wire format shared with the Java
// backend's PackingContractCodec. The fixtures under contracts/packing/v1
// are the source of truth; contracts_test.go round-trips them byte for
// byte.
package contracts

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
)

// MessageVersion is the pinned version every message on the packing wire
// carries, in both directions. It is exported so nothing outside this package
// has to restate it: a second copy is a second thing to bump.
const MessageVersion = 1

var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

var (
	ErrInvalidJSON        = errors.New("contracts: payload is not valid JSON")
	ErrUnsupportedVersion = errors.New("contracts: unsupported message version")
	ErrInvalidJobID       = errors.New("contracts: jobId must be a canonical UUID")
	ErrInvalidRuntime     = errors.New("contracts: maxRuntimeSeconds must be between 1 and 7200")
	ErrInvalidSpec        = errors.New("contracts: spec must be non-empty JSON")
)

type DispatchMessage struct {
	MessageVersion int    `json:"messageVersion"`
	JobID          string `json:"jobId"`
}

type RequestEnvelope struct {
	RequestVersion    int             `json:"requestVersion"`
	MaxRuntimeSeconds int64           `json:"maxRuntimeSeconds"`
	Spec              json.RawMessage `json:"spec"`
}

// The five optional fields are pointers, not plain values with omitempty.
// EncodeSucceeded/EncodeFailed always populate the fields their event type
// owns, including an explicit zero (0, ""); only a nil pointer (the fields
// a started event doesn't have) is omitted. A plain int64/string would be
// unable to tell "0 result bytes" or "empty reason" apart from "absent",
// and the committed Java decoder's requiredLong/requiredText treats a
// missing key as a hard decode failure.
type WorkerEvent struct {
	MessageVersion    int     `json:"messageVersion"`
	EventType         string  `json:"eventType"`
	JobID             string  `json:"jobId"`
	EngineVersion     string  `json:"engineVersion"`
	EngineChecksum    string  `json:"engineChecksum"`
	ResultFileName    *string `json:"resultFileName,omitempty"`
	ResultContentType *string `json:"resultContentType,omitempty"`
	ResultSizeBytes   *int64  `json:"resultSizeBytes,omitempty"`
	ResultChecksum    *string `json:"resultChecksum,omitempty"`
	Reason            *string `json:"reason,omitempty"`
}

func isCanonicalUUID(s string) bool {
	return uuidPattern.MatchString(s)
}

func DecodeDispatch(data []byte) (DispatchMessage, error) {
	var wire struct {
		MessageVersion *int    `json:"messageVersion"`
		JobID          *string `json:"jobId"`
	}
	if err := json.Unmarshal(data, &wire); err != nil {
		return DispatchMessage{}, fmt.Errorf("decode dispatch message: %w: %w", ErrInvalidJSON, err)
	}
	if wire.MessageVersion == nil || *wire.MessageVersion != MessageVersion {
		return DispatchMessage{}, fmt.Errorf("decode dispatch message: %w", ErrUnsupportedVersion)
	}
	if wire.JobID == nil || !isCanonicalUUID(*wire.JobID) {
		return DispatchMessage{}, fmt.Errorf("decode dispatch message: %w", ErrInvalidJobID)
	}
	return DispatchMessage{
		MessageVersion: *wire.MessageVersion,
		// Lowercased, not echoed back: the pattern accepts either case, but
		// Java's PackingJobId normalises through UUID.fromString, so the
		// canonical form is what PackingResultProcessor matches the event's
		// jobId against and what both blob keys are spelled with. Returning
		// the casing that arrived would put a different id in the session id,
		// in the event and in the keys than the one in the row.
		JobID: strings.ToLower(*wire.JobID),
	}, nil
}

func DecodeRequest(data []byte) (RequestEnvelope, error) {
	var wire struct {
		RequestVersion    *int            `json:"requestVersion"`
		MaxRuntimeSeconds *int64          `json:"maxRuntimeSeconds"`
		Spec              json.RawMessage `json:"spec"`
	}
	if err := json.Unmarshal(data, &wire); err != nil {
		return RequestEnvelope{}, fmt.Errorf("decode request envelope: %w: %w", ErrInvalidJSON, err)
	}
	if wire.RequestVersion == nil || *wire.RequestVersion != MessageVersion {
		return RequestEnvelope{}, fmt.Errorf("decode request envelope: %w", ErrUnsupportedVersion)
	}
	if wire.MaxRuntimeSeconds == nil || *wire.MaxRuntimeSeconds < 1 || *wire.MaxRuntimeSeconds > 7200 {
		return RequestEnvelope{}, fmt.Errorf("decode request envelope: %w", ErrInvalidRuntime)
	}
	if len(wire.Spec) == 0 || bytes.Equal(bytes.TrimSpace(wire.Spec), []byte("null")) {
		return RequestEnvelope{}, fmt.Errorf("decode request envelope: %w", ErrInvalidSpec)
	}
	return RequestEnvelope{
		RequestVersion:    *wire.RequestVersion,
		MaxRuntimeSeconds: *wire.MaxRuntimeSeconds,
		Spec:              wire.Spec,
	}, nil
}

func EncodeStarted(jobID, engineVersion, engineChecksum string) ([]byte, error) {
	return json.Marshal(WorkerEvent{
		MessageVersion: MessageVersion,
		EventType:      "started",
		JobID:          jobID,
		EngineVersion:  engineVersion,
		EngineChecksum: engineChecksum,
	})
}

func EncodeSucceeded(jobID, engineVersion, engineChecksum, resultFileName, resultContentType string, resultSizeBytes int64, resultChecksum string) ([]byte, error) {
	return json.Marshal(WorkerEvent{
		MessageVersion:    MessageVersion,
		EventType:         "succeeded",
		JobID:             jobID,
		EngineVersion:     engineVersion,
		EngineChecksum:    engineChecksum,
		ResultFileName:    &resultFileName,
		ResultContentType: &resultContentType,
		ResultSizeBytes:   &resultSizeBytes,
		ResultChecksum:    &resultChecksum,
	})
}

func EncodeFailed(jobID, engineVersion, engineChecksum, reason string) ([]byte, error) {
	return json.Marshal(WorkerEvent{
		MessageVersion: MessageVersion,
		EventType:      "failed",
		JobID:          jobID,
		EngineVersion:  engineVersion,
		EngineChecksum: engineChecksum,
		Reason:         &reason,
	})
}
