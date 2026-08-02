package pipeline

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
)

const (
	// messageVersion mirrors the pinned contract version in package
	// contracts, whose own constant is unexported. The tests compare every
	// event built here against that package's encoders byte for byte, so
	// the two cannot drift apart unnoticed.
	messageVersion = 1

	requestFileName = "request.json"
	specFileName    = "input.json"
	// outputFileName is the last segment of the result blob key
	// (packing-jobs/{jobId}/result/output), so the file name recorded in
	// the succeeded event is the name the result is stored under.
	outputFileName = "output"

	// unreportedFailure stands in for a packer failure that named no
	// reason. The backend rejects a blank one, and a rejected event leaves
	// the job RUNNING until the dead-letter reconciler notices.
	unreportedFailure = "packer failed without reporting a reason"
)

var (
	// ErrNoMessage means the dispatch queue had nothing for this run. It is
	// a normal outcome of a one-shot worker, not a failure.
	ErrNoMessage = errors.New("pipeline: no dispatch message available")

	// ErrLockLost means the delivery's lock could not be renewed, so the
	// broker may already have handed this job to someone else. Neither
	// settlement call may follow it.
	ErrLockLost = errors.New("pipeline: dispatch lock renewal failed")
)

// settlement is what the broker is told once the job's own work is over.
// The zero value is deliberately settleAbandon: a path that never reaches a
// decision asks for a redelivery instead of silently dropping a job.
type settlement int

const (
	settleAbandon settlement = iota
	settleComplete
)

// Processor handles exactly one delivery per call. Retry belongs to the
// broker: abandoning is how this worker asks for a redelivery, completing
// is how it says the job reached a terminal state — successfully or not.
type Processor struct {
	queue             Queue
	artifacts         Artifacts
	engine            Engine
	workRoot          string
	lockRenewInterval time.Duration
}

func NewProcessor(queue Queue, artifacts Artifacts, engine Engine, workRoot string, lockRenewInterval time.Duration) *Processor {
	return &Processor{
		queue:             queue,
		artifacts:         artifacts,
		engine:            engine,
		workRoot:          workRoot,
		lockRenewInterval: lockRenewInterval,
	}
}

func (p *Processor) RunOnce(ctx context.Context) error {
	if p.lockRenewInterval <= 0 {
		// Checked before receiving: a message taken from the queue and then
		// dropped by a panicking time.NewTicker would sit locked until the
		// broker expired it.
		return fmt.Errorf("pipeline: lock renew interval must be positive, got %s", p.lockRenewInterval)
	}

	delivery, err := p.queue.ReceiveOne(ctx)
	if err != nil {
		return err
	}
	if delivery == nil {
		return ErrNoMessage
	}

	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	renewal := p.startRenewal(runCtx, cancel, delivery)

	decision, err := p.handle(runCtx, delivery)

	// Join the renewal goroutine before settling anything. A lock that is
	// already gone makes both settlement calls wrong — the broker may have
	// redelivered this job to another worker — and whether it is gone is
	// only known once the goroutine has stopped.
	if lockErr := renewal.stop(); lockErr != nil {
		return lockErr
	}

	if decision == settleComplete {
		if settleErr := p.queue.Complete(runCtx, delivery); settleErr != nil {
			return errors.Join(err, fmt.Errorf("pipeline: complete dispatch: %w", settleErr))
		}
		return err
	}
	if settleErr := p.queue.Abandon(runCtx, delivery); settleErr != nil {
		return errors.Join(err, fmt.Errorf("pipeline: abandon dispatch: %w", settleErr))
	}
	return err
}

// handle runs the job and reports its outcome, but never settles the
// message: settlement is RunOnce's, because only RunOnce knows whether the
// lock survived.
func (p *Processor) handle(ctx context.Context, delivery Delivery) (settlement, error) {
	dispatch, err := contracts.DecodeDispatch(delivery.Body())
	if err != nil {
		// Abandoning a message this worker cannot parse leaves retry
		// exhaustion to the broker's delivery count and dead-letter policy.
		return settleAbandon, err
	}
	jobID := dispatch.JobID

	stored, err := p.artifacts.FindResult(ctx, jobID)
	if err != nil {
		return settleAbandon, fmt.Errorf("pipeline: look up result for job %s: %w", jobID, err)
	}
	if stored != nil {
		return p.reportStored(ctx, jobID, *stored)
	}

	workspace, err := os.MkdirTemp(p.workRoot, "packing-"+jobID+"-")
	if err != nil {
		return settleAbandon, fmt.Errorf("pipeline: create workspace for job %s: %w", jobID, err)
	}
	// Only the directory MkdirTemp returned, never the configured root.
	defer os.RemoveAll(workspace)

	envelope, err := p.fetchRequest(ctx, jobID, workspace)
	if err != nil {
		return settleAbandon, err
	}

	specPath := filepath.Join(workspace, specFileName)
	// envelope.Spec goes to disk as the bytes that arrived. Re-encoding it
	// would HTML-escape < and & inside a spec this worker is not entitled
	// to interpret.
	if err := os.WriteFile(specPath, envelope.Spec, 0o600); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: write packer input for job %s: %w", jobID, err)
	}

	// Under ctx, not context.Background(): Provenance has no timeout of its
	// own, and a packer that hangs on --version would otherwise hold the
	// worker until its lock quietly expired.
	engine, err := p.engine.Provenance(ctx)
	if err != nil {
		return settleAbandon, fmt.Errorf("pipeline: read packer provenance for job %s: %w", jobID, err)
	}

	if err := p.queue.SendEvent(ctx, startedEvent(jobID, engine)); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: send started event for job %s: %w", jobID, err)
	}

	outputPath := filepath.Join(workspace, outputFileName)
	result, err := p.engine.Run(ctx, RunRequest{
		SpecPath:   specPath,
		OutputPath: outputPath,
		// The only deadline on this context is the lost-lock cancellation.
		// A competing one here would make a withdrawn job indistinguishable
		// from a packer that outran its limit.
		Runtime: time.Duration(envelope.MaxRuntimeSeconds) * time.Second,
	})
	if err != nil {
		var failure *EngineFailure
		// ctx.Err() guards the classification as well as errors.As: once the
		// job has been withdrawn, whatever the packer reported is not the
		// job's own verdict, and reporting `failed` would mark a job failed
		// that never failed.
		if errors.As(err, &failure) && ctx.Err() == nil {
			return p.reportFailure(ctx, jobID, engine, failure.Reason)
		}
		return settleAbandon, fmt.Errorf("pipeline: run packer for job %s: %w", jobID, err)
	}

	created, err := p.artifacts.CreateResult(ctx, jobID, outputPath, StoredResult{Engine: engine, Result: result})
	if err != nil {
		return settleAbandon, fmt.Errorf("pipeline: upload result for job %s: %w", jobID, err)
	}
	if created == nil {
		return settleAbandon, fmt.Errorf("pipeline: upload result for job %s: no metadata reported", jobID)
	}

	// created, not result: when another delivery won the conditional create,
	// the bytes in storage are its bytes, and the event has to describe what
	// the backend will hand the user.
	if err := p.queue.SendEvent(ctx, succeededEvent(jobID, created.Engine, created.Result)); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: send succeeded event for job %s: %w", jobID, err)
	}
	return settleComplete, nil
}

// reportStored answers a redelivery whose result is already in storage —
// the message was redelivered after a crash, or after a lost lock. Both
// events are resent from the stored metadata: the backend tolerates a
// repeat, and staying silent would leave the job RUNNING forever.
func (p *Processor) reportStored(ctx context.Context, jobID string, stored StoredResult) (settlement, error) {
	if err := p.queue.SendEvent(ctx, startedEvent(jobID, stored.Engine)); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: resend started event for job %s: %w", jobID, err)
	}
	if err := p.queue.SendEvent(ctx, succeededEvent(jobID, stored.Engine, stored.Result)); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: resend succeeded event for job %s: %w", jobID, err)
	}
	return settleComplete, nil
}

// reportFailure records a packing job the packer rejected. That is a normal
// terminal outcome, so the delivery is completed rather than retried.
func (p *Processor) reportFailure(ctx context.Context, jobID string, engine EngineProvenance, reason string) (settlement, error) {
	if strings.TrimSpace(reason) == "" {
		reason = unreportedFailure
	}
	if err := p.queue.SendEvent(ctx, failedEvent(jobID, engine, reason)); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: send failed event for job %s: %w", jobID, err)
	}
	return settleComplete, nil
}

func (p *Processor) fetchRequest(ctx context.Context, jobID, workspace string) (contracts.RequestEnvelope, error) {
	path := filepath.Join(workspace, requestFileName)
	if err := p.artifacts.DownloadRequest(ctx, jobID, path); err != nil {
		return contracts.RequestEnvelope{}, fmt.Errorf("pipeline: download request for job %s: %w", jobID, err)
	}
	body, err := os.ReadFile(path)
	if err != nil {
		return contracts.RequestEnvelope{}, fmt.Errorf("pipeline: read request for job %s: %w", jobID, err)
	}
	envelope, err := contracts.DecodeRequest(body)
	if err != nil {
		return contracts.RequestEnvelope{}, fmt.Errorf("pipeline: job %s: %w", jobID, err)
	}
	return envelope, nil
}

// lockRenewal holds the delivery's lock for as long as the job runs.
type lockRenewal struct {
	stopOnce sync.Once
	stopped  chan struct{}
	done     chan struct{}
	// err is written before done is closed and read after it closes, so the
	// channel carries the happens-before edge.
	err error
}

func (p *Processor) startRenewal(ctx context.Context, cancel context.CancelFunc, delivery Delivery) *lockRenewal {
	renewal := &lockRenewal{
		stopped: make(chan struct{}),
		done:    make(chan struct{}),
	}
	ticker := time.NewTicker(p.lockRenewInterval)

	go func() {
		defer close(renewal.done)
		defer ticker.Stop()
		for {
			select {
			case <-renewal.stopped:
				return
			case <-ctx.Done():
				return
			case <-ticker.C:
				err := p.queue.RenewLock(ctx, delivery)
				if err == nil {
					continue
				}
				if ctx.Err() != nil {
					// The context was withdrawn under the renewal — a
					// shutdown, not a lock this worker lost. Saying
					// ErrLockLost here would suppress the abandon that a
					// shutdown should still produce.
					return
				}
				// Reported once, and once only: the goroutine stops at the
				// first failure rather than retrying a lock the broker has
				// most likely already reassigned.
				renewal.err = fmt.Errorf("%w: %w", ErrLockLost, err)
				cancel()
				return
			}
		}
	}()

	return renewal
}

// stop halts the ticker, waits for the goroutine, and reports the renewal
// failure if there was one. Nothing may be settled before it returns.
func (r *lockRenewal) stop() error {
	r.stopOnce.Do(func() { close(r.stopped) })
	<-r.done
	return r.err
}

func startedEvent(jobID string, engine EngineProvenance) contracts.WorkerEvent {
	return contracts.WorkerEvent{
		MessageVersion: messageVersion,
		EventType:      "started",
		JobID:          jobID,
		EngineVersion:  engine.Version,
		EngineChecksum: engine.Checksum,
	}
}

func succeededEvent(jobID string, engine EngineProvenance, result EngineResult) contracts.WorkerEvent {
	return contracts.WorkerEvent{
		MessageVersion:    messageVersion,
		EventType:         "succeeded",
		JobID:             jobID,
		EngineVersion:     engine.Version,
		EngineChecksum:    engine.Checksum,
		ResultFileName:    &result.FileName,
		ResultContentType: &result.ContentType,
		ResultSizeBytes:   &result.SizeBytes,
		ResultChecksum:    &result.Checksum,
	}
}

func failedEvent(jobID string, engine EngineProvenance, reason string) contracts.WorkerEvent {
	return contracts.WorkerEvent{
		MessageVersion: messageVersion,
		EventType:      "failed",
		JobID:          jobID,
		EngineVersion:  engine.Version,
		EngineChecksum: engine.Checksum,
		Reason:         &reason,
	}
}
