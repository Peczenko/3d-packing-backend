package pipeline

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
)

const (
	requestFileName = "request.json"
	specFileName    = "input.json"
	// outputFileName is the last segment of the result blob key
	// (packing-jobs/{jobId}/result/output), so the file name recorded in
	// the succeeded event is the name the result is stored under.
	outputFileName = "output"

	// unreportedFailure stands in for a packer failure that named no
	// reason. PackingJob.fail rejects a blank one, so the event would be
	// dead-lettered and the job would never reach a terminal state.
	unreportedFailure = "packer failed without reporting a reason"

	// settleTimeout bounds the settlement call that runs after a shutdown
	// has already canceled the caller's context.
	settleTimeout = 30 * time.Second
)

// sha256Hex mirrors PackingJob.SHA_256. An event carrying anything else is
// rejected by the backend's domain model, not by its decoder, so it is
// dead-lettered on the result queue where nothing can settle it.
var sha256Hex = regexp.MustCompile(`^[0-9a-fA-F]{64}$`)

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

	runCtx, withdrawJob := context.WithCancel(ctx)
	defer withdrawJob()

	// The renewal hangs off ctx rather than runCtx, and owns a context of
	// its own that stop() cancels. Renewing under runCtx would deadlock: a
	// RenewLock that never returns cannot see a stop signal, and the only
	// cancel of runCtx is the deferred one that cannot run until RunOnce
	// has returned.
	renewal := p.startRenewal(ctx, withdrawJob, delivery)
	// Idempotent, and the goroutine is joined even if a later edit adds an
	// early return between here and the explicit stop below.
	defer func() { _ = renewal.stop() }()

	decision, err := p.handle(runCtx, delivery)

	// Join the renewal goroutine before settling anything. A lock that is
	// already gone makes both settlement calls wrong — the broker may have
	// redelivered this job to another worker — and whether it is gone is
	// only known once the goroutine has stopped.
	if lockErr := renewal.stop(); lockErr != nil {
		return lockErr
	}

	// Settlement outlives a shutdown. The job's work is already over by
	// here, and skipping the call because SIGTERM canceled ctx would leave
	// the message locked for its full lock duration before the broker
	// redelivered work that is finished.
	settleCtx, cancelSettle := context.WithTimeout(context.WithoutCancel(ctx), settleTimeout)
	defer cancelSettle()

	if decision == settleComplete {
		if settleErr := p.queue.Complete(settleCtx, delivery); settleErr != nil {
			return errors.Join(err, fmt.Errorf("pipeline: complete dispatch: %w", settleErr))
		}
		return err
	}
	if settleErr := p.queue.Abandon(settleCtx, delivery); settleErr != nil {
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
		return settleAbandon, fmt.Errorf("pipeline: %w", err)
	}
	jobID := dispatch.JobID

	stored, err := p.artifacts.FindResult(ctx, jobID)
	if err != nil {
		return settleAbandon, fmt.Errorf("pipeline: look up result for job %s: %w", jobID, err)
	}
	if stored != nil {
		if err := validateStoredResult(*stored); err != nil {
			return settleAbandon, fmt.Errorf("pipeline: stored result for job %s is unusable: %w", jobID, err)
		}
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
	// Checked before the started event, while the job is still QUEUED: a
	// provenance the backend's domain model rejects would otherwise be sent
	// on the result queue, where a dead-lettered event settles nothing and
	// the job never reaches a terminal state.
	if err := validateProvenance(engine); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: packer provenance for job %s is unusable: %w", jobID, err)
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
	if err := validateStoredResult(*created); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: stored result for job %s is unusable: %w", jobID, err)
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

// validateStoredResult and validateProvenance enforce, on this side of the
// wire, what PackingJob.markRunning and PackingJob.succeed enforce on the
// other. These values come from blob metadata written by another process,
// so nothing else in the worker has seen them. An event the backend's
// domain model rejects is dead-lettered on the result queue, and the result
// dead-letter reconciler leaves such a message for inspection rather than
// settling it, so it is re-logged forever and the job is never terminal.
// Refusing to send it is what keeps that from happening.
func validateStoredResult(stored StoredResult) error {
	if err := validateProvenance(stored.Engine); err != nil {
		return err
	}
	if strings.TrimSpace(stored.Result.FileName) == "" {
		return errors.New("result file name is blank")
	}
	if strings.TrimSpace(stored.Result.ContentType) == "" {
		return errors.New("result content type is blank")
	}
	if stored.Result.SizeBytes <= 0 {
		return fmt.Errorf("result size %d is not positive", stored.Result.SizeBytes)
	}
	if !sha256Hex.MatchString(stored.Result.Checksum) {
		return fmt.Errorf("result checksum %q is not a SHA-256 hex string", stored.Result.Checksum)
	}
	return nil
}

func validateProvenance(engine EngineProvenance) error {
	if strings.TrimSpace(engine.Version) == "" {
		return errors.New("engine version is blank")
	}
	if !sha256Hex.MatchString(engine.Checksum) {
		return fmt.Errorf("engine checksum %q is not a SHA-256 hex string", engine.Checksum)
	}
	return nil
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
	// cancel ends the renewal's own context, which is both how the loop is
	// told to stop and how an in-flight RenewLock is interrupted.
	cancel context.CancelFunc
	done   chan struct{}
	// err is written before done is closed and read after it closes, so the
	// channel carries the happens-before edge.
	err error
}

func (p *Processor) startRenewal(ctx context.Context, withdrawJob context.CancelFunc, delivery Delivery) *lockRenewal {
	renewCtx, stopRenewal := context.WithCancel(ctx)
	renewal := &lockRenewal{
		cancel: stopRenewal,
		done:   make(chan struct{}),
	}
	ticker := time.NewTicker(p.lockRenewInterval)

	go func() {
		defer close(renewal.done)
		defer ticker.Stop()
		for {
			select {
			case <-renewCtx.Done():
				return
			case <-ticker.C:
				err := p.queue.RenewLock(renewCtx, delivery)
				if err == nil {
					continue
				}
				if renewCtx.Err() != nil {
					// The renewal's own context was withdrawn — a shutdown,
					// or stop() interrupting a call whose job is already
					// over. Neither is a lock this worker lost, and saying
					// ErrLockLost here would suppress a settlement that
					// should still happen.
					return
				}
				// Reported once, and once only: the goroutine stops at the
				// first failure rather than retrying a lock the broker has
				// most likely already reassigned.
				renewal.err = fmt.Errorf("%w: %w", ErrLockLost, err)
				withdrawJob()
				return
			}
		}
	}()

	return renewal
}

// stop cancels the renewal, waits for the goroutine, and reports the
// renewal failure if there was one. Nothing may be settled before it
// returns, and it is safe to call more than once.
func (r *lockRenewal) stop() error {
	r.cancel()
	<-r.done
	return r.err
}

func startedEvent(jobID string, engine EngineProvenance) contracts.WorkerEvent {
	return contracts.WorkerEvent{
		MessageVersion: contracts.MessageVersion,
		EventType:      "started",
		JobID:          jobID,
		EngineVersion:  engine.Version,
		EngineChecksum: engine.Checksum,
	}
}

func succeededEvent(jobID string, engine EngineProvenance, result EngineResult) contracts.WorkerEvent {
	return contracts.WorkerEvent{
		MessageVersion:    contracts.MessageVersion,
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
		MessageVersion: contracts.MessageVersion,
		EventType:      "failed",
		JobID:          jobID,
		EngineVersion:  engine.Version,
		EngineChecksum: engine.Checksum,
		Reason:         &reason,
	}
}
