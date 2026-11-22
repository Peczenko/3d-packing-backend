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
	WorkDir       string
	Runtime       time.Duration
}

type Packing struct {
	Layout             Layout
	PreparationSeconds float64
	SearchSeconds      float64
}

type ResultFiles struct {
	FileName        string
	ContentType     string
	ContentEncoding string
	Path            string
	SizeBytes       int64
	Checksum        string
	StoredBytes     int64
	Bins            []ResultBin
}

type Placement struct {
	Item   int
	Bin    int
	Matrix [12]float64
}

type Layout struct {
	BinOffsets [][3]float64
	Placements []Placement
}

type ResultBin struct {
	Path      string
	SizeBytes int64
	Checksum  string
}

type Engine interface {
	Provenance(context.Context) (EngineProvenance, error)
	Run(context.Context, RunRequest) (Packing, error)
}

type ResultWriter interface {
	Write(ctx context.Context, inputs []InputFile, layout Layout, dir string) (ResultFiles, error)
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
	FindPlacements(context.Context, string) (*StoredPlacements, error)
	CreatePlacements(context.Context, string, StoredPlacements) (*StoredPlacements, error)
	FindResult(context.Context, string) (*StoredResult, error)
	CreateResult(context.Context, string, StoredResult) (*StoredResult, error)
}

type StoredPlacements struct {
	Engine EngineProvenance
	Layout Layout
}

type StoredResult struct {
	Engine EngineProvenance
	Result ResultFiles
}
