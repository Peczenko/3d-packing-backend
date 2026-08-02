// Package pipeline declares the ports the packing worker orchestrates and
// the value types that cross them. Adapters import this package; it must
// never import them, so the processor can live here without a cycle.
package pipeline

import (
	"context"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
)

// EngineProvenance identifies the packer that produced a result: the
// verbatim `packer --version` line and the SHA-256 of the executable's own
// bytes. The packer is a replaceable external binary, so the pair is the
// only durable record of which engine ran a given job.
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

// EngineFailure marks the two outcomes that are the packing job's own
// failure rather than the worker's: the packer exited nonzero, and the
// packer outran its runtime limit. Nothing else may be an EngineFailure —
// not a missing executable, not an unreadable output file, not a canceled
// parent context. The processor branches on errors.As to choose between
// reporting a failed job and completing the dispatch message, and
// abandoning the message so the broker redelivers it; classifying an
// infrastructure error as an EngineFailure loses a job that deserved a
// retry, and the reverse retries a doomed job until the dead-letter queue
// catches it. Reason becomes the failed event's `reason` verbatim, and the
// Java side rejects a blank one, so it is never empty.
type EngineFailure struct {
	Reason string
}

func (f *EngineFailure) Error() string {
	return f.Reason
}

// Delivery is one broker message. It exposes only its body: the job id is
// read from the payload and never from a subject, session or application
// property, so a message cannot claim to be about a job its body does not
// name.
type Delivery interface {
	Body() []byte
}

// Queue is the dispatch/result broker. Settlement is manual and explicit:
// Complete says the job reached a terminal state, Abandon asks for a
// redelivery, and neither is called when the lock is already lost.
type Queue interface {
	ReceiveOne(context.Context) (Delivery, error)
	RenewLock(context.Context, Delivery) error
	SendEvent(context.Context, contracts.WorkerEvent) error
	Complete(context.Context, Delivery) error
	Abandon(context.Context, Delivery) error
}

// Artifacts is the request/result blob store, addressed by job id: the
// adapter owns the key layout, so nothing here can derive a key that
// disagrees with it.
type Artifacts interface {
	DownloadRequest(context.Context, string, string) error
	// FindResult reports (nil, nil) when the job has no result yet. A
	// non-nil error is always infrastructure, never absence.
	FindResult(context.Context, string) (*StoredResult, error)
	// CreateResult uploads conditionally and returns the created metadata,
	// or the metadata already stored when another delivery won the race.
	// The loser must report the winner's result rather than its own.
	CreateResult(context.Context, string, string, StoredResult) (*StoredResult, error)
}

// StoredResult is what a finished job leaves behind: which packer ran, and
// what it produced. It is the whole succeeded event, so a redelivered
// message can report an outcome it did not compute itself.
type StoredResult struct {
	Engine EngineProvenance
	Result EngineResult
}
