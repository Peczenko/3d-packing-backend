package contracts

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
)

const (
	ActivityVersion = 2
	RequestVersion  = 1

	RunEngineActivity   = "RunEngine"
	EngineStartedSignal = "engineStarted"
	EngineFailureType   = "EngineFailure"
	InvalidInputType    = "InvalidInput"
)

var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

var (
	ErrInvalidJSON        = errors.New("contracts: payload is not valid JSON")
	ErrUnsupportedVersion = errors.New("contracts: unsupported contract version")
	ErrInvalidJobID       = errors.New("contracts: jobId must be a canonical UUID")
	ErrInvalidRuntime     = errors.New("contracts: maxRuntimeSeconds must be between 1 and 7200")
	ErrInvalidSpec        = errors.New("contracts: spec must be non-empty JSON")
)

type RunEngineInput struct {
	ContractVersion int    `json:"contractVersion"`
	JobID           string `json:"jobId"`
}

type EngineStarted struct {
	ContractVersion int    `json:"contractVersion"`
	EngineVersion   string `json:"engineVersion"`
	EngineChecksum  string `json:"engineChecksum"`
}

type EngineResult struct {
	ContractVersion   int    `json:"contractVersion"`
	EngineVersion     string `json:"engineVersion"`
	EngineChecksum    string `json:"engineChecksum"`
	ResultFileName    string `json:"resultFileName"`
	ResultContentType string `json:"resultContentType"`
	ResultSizeBytes   int64  `json:"resultSizeBytes"`
	ResultChecksum    string `json:"resultChecksum"`
}

type EngineFailureDetails struct {
	ContractVersion int    `json:"contractVersion"`
	EngineVersion   string `json:"engineVersion"`
	EngineChecksum  string `json:"engineChecksum"`
	Reason          string `json:"reason"`
}

type RequestEnvelope struct {
	RequestVersion    int             `json:"requestVersion"`
	MaxRuntimeSeconds int64           `json:"maxRuntimeSeconds"`
	Spec              json.RawMessage `json:"spec"`
}

func ValidateRunEngineInput(input RunEngineInput) (RunEngineInput, error) {
	if input.ContractVersion != ActivityVersion {
		return RunEngineInput{}, fmt.Errorf("run engine input: %w", ErrUnsupportedVersion)
	}
	if !uuidPattern.MatchString(input.JobID) {
		return RunEngineInput{}, fmt.Errorf("run engine input: %w", ErrInvalidJobID)
	}
	return RunEngineInput{ContractVersion: input.ContractVersion, JobID: strings.ToLower(input.JobID)}, nil
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
	if wire.RequestVersion == nil || *wire.RequestVersion != RequestVersion {
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
