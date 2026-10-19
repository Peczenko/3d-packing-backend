package pipeline

import (
	"context"
	"errors"
	"time"
)

var ErrInputNotFound = errors.New("pipeline: input file is not stored")

type EngineProvenance struct {
	Version  string
	Checksum string
}

type InputFile struct {
	Path        string
	DisplayName string
	Copies      int
}

type RunRequest struct {
	Inputs        []InputFile
	ParameterArgs []string
	OutputPath    string
	Runtime       time.Duration
}

type EngineResult struct {
	FileName    string
	ContentType string
	SizeBytes   int64
	Checksum    string
}

type Engine interface {
	Provenance(context.Context) (EngineProvenance, error)
	Run(context.Context, RunRequest) (EngineResult, error)
}

type EngineFailure struct {
	Reason string
}

func (f *EngineFailure) Error() string {
	return f.Reason
}

type Signaler interface {
	SignalWorkflow(ctx context.Context, workflowID, runID, signalName string, arg any) error
}

type Artifacts interface {
	DownloadRequest(context.Context, string, string) error
	DownloadInput(ctx context.Context, storageKey, path string) error
	FindResult(context.Context, string) (*StoredResult, error)
	CreateResult(context.Context, string, string, StoredResult) (*StoredResult, error)
}

type StoredResult struct {
	Engine EngineProvenance
	Result EngineResult
}
