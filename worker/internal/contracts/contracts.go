package contracts

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
)

const (
	ActivityVersion = 3
	RequestVersion  = 3

	RunEngineActivity   = "RunEngine"
	EngineStartedSignal = "engineStarted"
	EngineFailureType   = "EngineFailure"
	InvalidInputType    = "InvalidInput"

	MinRuntimeSeconds = 1
	MaxRuntimeSeconds = 7200
	MaxItems          = 50
	MinCopies         = 1
	MaxCopies         = 100
	MaxTotalCopies    = 200

	storageKeyPrefix = "files/"
)

var (
	uuidPattern   = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
	sha256Pattern = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

var (
	ErrInvalidJSON          = errors.New("contracts: payload is not valid JSON")
	ErrUnsupportedVersion   = errors.New("contracts: unsupported contract version")
	ErrInvalidJobID         = errors.New("contracts: jobId must be a canonical UUID")
	ErrInvalidRuntime       = errors.New("contracts: maxRuntimeSeconds must be between 1 and 7200")
	ErrInvalidSchemaVersion = errors.New("contracts: inputSchemaVersion must be at least 1")
	ErrInvalidItems         = errors.New("contracts: items are outside the input limits")
	ErrUnsupportedFormat    = errors.New("contracts: an item's format is not accepted by the engine")
	ErrInvalidParameters    = errors.New("contracts: parameters are not accepted by the engine")
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

type RequestItem struct {
	StorageKey string `json:"storageKey"`
	FileName   string `json:"fileName"`
	Format     string `json:"format"`
	Checksum   string `json:"checksum"`
	Copies     int    `json:"copies"`
}

type RequestEnvelope struct {
	RequestVersion     int             `json:"requestVersion"`
	MaxRuntimeSeconds  int64           `json:"maxRuntimeSeconds"`
	InputSchemaVersion int             `json:"inputSchemaVersion"`
	Items              []RequestItem   `json:"items"`
	Parameters         json.RawMessage `json:"parameters"`
	ParameterArgs      []string        `json:"-"`
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
		RequestVersion     *int            `json:"requestVersion"`
		MaxRuntimeSeconds  *int64          `json:"maxRuntimeSeconds"`
		InputSchemaVersion *int            `json:"inputSchemaVersion"`
		Items              []RequestItem   `json:"items"`
		Parameters         json.RawMessage `json:"parameters"`
	}
	if err := json.Unmarshal(data, &wire); err != nil {
		return RequestEnvelope{}, fmt.Errorf("decode request envelope: %w: %w", ErrInvalidJSON, err)
	}
	if wire.RequestVersion == nil || *wire.RequestVersion != RequestVersion {
		return RequestEnvelope{}, fmt.Errorf("decode request envelope: %w", ErrUnsupportedVersion)
	}
	if wire.MaxRuntimeSeconds == nil || *wire.MaxRuntimeSeconds < MinRuntimeSeconds || *wire.MaxRuntimeSeconds > MaxRuntimeSeconds {
		return RequestEnvelope{}, fmt.Errorf("decode request envelope: %w", ErrInvalidRuntime)
	}
	if wire.InputSchemaVersion == nil || *wire.InputSchemaVersion < 1 {
		return RequestEnvelope{}, fmt.Errorf("decode request envelope: %w", ErrInvalidSchemaVersion)
	}
	if err := validateItems(wire.Items); err != nil {
		return RequestEnvelope{}, fmt.Errorf("decode request envelope: %w", err)
	}
	args, err := ParameterArgs(wire.Parameters)
	if err != nil {
		return RequestEnvelope{}, fmt.Errorf("decode request envelope: %w", err)
	}
	return RequestEnvelope{
		RequestVersion:     *wire.RequestVersion,
		MaxRuntimeSeconds:  *wire.MaxRuntimeSeconds,
		InputSchemaVersion: *wire.InputSchemaVersion,
		Items:              wire.Items,
		Parameters:         wire.Parameters,
		ParameterArgs:      args,
	}, nil
}

func validateItems(items []RequestItem) error {
	if len(items) < 1 || len(items) > MaxItems {
		return fmt.Errorf("%w: %d items", ErrInvalidItems, len(items))
	}
	total := 0
	for i, item := range items {
		switch {
		case item.Copies < MinCopies || item.Copies > MaxCopies:
			return fmt.Errorf("%w: item %d has %d copies", ErrInvalidItems, i, item.Copies)
		case !strings.HasPrefix(item.StorageKey, storageKeyPrefix) || strings.Contains(item.StorageKey, ".."):
			return fmt.Errorf("%w: item %d is not stored under %s", ErrInvalidItems, i, storageKeyPrefix)
		case strings.TrimSpace(item.FileName) == "":
			return fmt.Errorf("%w: item %d has no file name", ErrInvalidItems, i)
		case !sha256Pattern.MatchString(item.Checksum):
			return fmt.Errorf("%w: item %d checksum is not a SHA-256 hex string", ErrInvalidItems, i)
		}
		if _, ok := AcceptedFormats[item.Format]; !ok {
			return fmt.Errorf("%w: item %d is %s", ErrUnsupportedFormat, i, item.Format)
		}
		total += item.Copies
	}
	if total > MaxTotalCopies {
		return fmt.Errorf("%w: %d objects in total", ErrInvalidItems, total)
	}
	return nil
}
