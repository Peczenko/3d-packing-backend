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
	requestFileName       = "request.json"
	specFileName          = "input.json"
	outputFileName        = "output"
	unreportedFailure     = "packer failed without reporting a reason"
	settleTimeout         = 30 * time.Second
	maxEngineVersionBytes = 255
)

func isControlCharacter(r rune) bool {
	return r < 0x20 || r == 0x7f
}

func stripControlCharacters(reason string) string {
	return strings.TrimSpace(strings.Map(func(r rune) rune {
		if isControlCharacter(r) {
			return ' '
		}
		return r
	}, reason))
}

var sha256Hex = regexp.MustCompile(`^[0-9a-fA-F]{64}$`)

var (
	ErrNoMessage = errors.New("pipeline: no dispatch message available")

	ErrLockLost = errors.New("pipeline: dispatch lock renewal failed")
)

type settlement int

const (
	settleAbandon settlement = iota
	settleComplete
)

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

	renewal := p.startRenewal(ctx, withdrawJob, delivery)
	defer func() { _ = renewal.stop() }()

	decision, err := p.handle(runCtx, delivery)

	if lockErr := renewal.stop(); lockErr != nil {
		return errors.Join(err, lockErr)
	}

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
	defer os.RemoveAll(workspace)

	envelope, err := p.fetchRequest(ctx, jobID, workspace)
	if err != nil {
		return settleAbandon, err
	}

	specPath := filepath.Join(workspace, specFileName)
	if err := os.WriteFile(specPath, envelope.Spec, 0o600); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: write packer input for job %s: %w", jobID, err)
	}

	engine, err := p.engine.Provenance(ctx)
	if err != nil {
		return settleAbandon, fmt.Errorf("pipeline: read packer provenance for job %s: %w", jobID, err)
	}
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
		Runtime:    time.Duration(envelope.MaxRuntimeSeconds) * time.Second,
	})
	if err != nil {
		var failure *EngineFailure
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

	if err := p.queue.SendEvent(ctx, succeededEvent(jobID, created.Engine, created.Result)); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: send succeeded event for job %s: %w", jobID, err)
	}
	return settleComplete, nil
}

func (p *Processor) reportStored(ctx context.Context, jobID string, stored StoredResult) (settlement, error) {
	if err := p.queue.SendEvent(ctx, startedEvent(jobID, stored.Engine)); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: resend started event for job %s: %w", jobID, err)
	}
	if err := p.queue.SendEvent(ctx, succeededEvent(jobID, stored.Engine, stored.Result)); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: resend succeeded event for job %s: %w", jobID, err)
	}
	return settleComplete, nil
}

func (p *Processor) reportFailure(ctx context.Context, jobID string, engine EngineProvenance, reason string) (settlement, error) {
	reason = stripControlCharacters(reason)
	if reason == "" {
		reason = unreportedFailure
	}
	if err := p.queue.SendEvent(ctx, failedEvent(jobID, engine, reason)); err != nil {
		return settleAbandon, fmt.Errorf("pipeline: send failed event for job %s: %w", jobID, err)
	}
	return settleComplete, nil
}

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
	if i := strings.IndexFunc(engine.Version, isControlCharacter); i >= 0 {
		return fmt.Errorf("engine version carries a control character at byte %d", i)
	}
	if len(engine.Version) > maxEngineVersionBytes {
		return fmt.Errorf("engine version is %d bytes, over the %d packing_jobs.engine_version holds", len(engine.Version), maxEngineVersionBytes)
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

type lockRenewal struct {
	cancel context.CancelFunc
	done   chan struct{}
	err    error
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
				renewal.err = fmt.Errorf("%w: %w", ErrLockLost, err)
				withdrawJob()
				return
			}
		}
	}()

	return renewal
}

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
