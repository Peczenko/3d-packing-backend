// Package pipeline declares the ports the packing worker orchestrates and
// the value types that cross them. Adapters import this package; it must
// never import them, so the processor can live here without a cycle.
package pipeline

import (
	"context"
	"time"
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
