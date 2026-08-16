package pipeline

import (
	"context"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
)

type EngineProvenance struct {
	Version  string
	Checksum string
}

type RunRequest struct {
	SpecPath   string
	OutputPath string
	Runtime    time.Duration
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

type Delivery interface {
	Body() []byte
}

type Queue interface {
	ReceiveOne(context.Context) (Delivery, error)
	RenewLock(context.Context, Delivery) error
	SendEvent(context.Context, contracts.WorkerEvent) error
	Complete(context.Context, Delivery) error
	Abandon(context.Context, Delivery) error
}

type Artifacts interface {
	DownloadRequest(context.Context, string, string) error
	FindResult(context.Context, string) (*StoredResult, error)
	CreateResult(context.Context, string, string, StoredResult) (*StoredResult, error)
}

type StoredResult struct {
	Engine EngineProvenance
	Result EngineResult
}
