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

	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/temporal"

	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
)

const (
	requestFileName       = "request.json"
	specFileName          = "input.json"
	outputFileName        = "output"
	unreportedFailure     = "packer failed without reporting a reason"
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

type Activity struct {
	artifacts         Artifacts
	engine            Engine
	signaler          Signaler
	workRoot          string
	heartbeatInterval time.Duration
}

func NewActivity(artifacts Artifacts, engine Engine, signaler Signaler, workRoot string, heartbeatInterval time.Duration) *Activity {
	return &Activity{
		artifacts:         artifacts,
		engine:            engine,
		signaler:          signaler,
		workRoot:          workRoot,
		heartbeatInterval: heartbeatInterval,
	}
}

func (a *Activity) RunEngine(ctx context.Context, input contracts.RunEngineInput) (contracts.EngineResult, error) {
	if a.heartbeatInterval <= 0 {
		return contracts.EngineResult{}, fmt.Errorf("pipeline: heartbeat interval must be positive, got %s", a.heartbeatInterval)
	}
	in, err := contracts.ValidateRunEngineInput(input)
	if err != nil {
		return contracts.EngineResult{}, temporal.NewNonRetryableApplicationError(err.Error(), contracts.InvalidInputType, err)
	}

	stopHeartbeat := a.startHeartbeat(ctx)
	defer stopHeartbeat()

	stored, err := a.handle(ctx, in.JobID)
	if err != nil {
		return contracts.EngineResult{}, err
	}
	return toEngineResult(stored), nil
}

func (a *Activity) handle(ctx context.Context, jobID string) (StoredResult, error) {
	stored, err := a.artifacts.FindResult(ctx, jobID)
	if err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: look up result for job %s: %w", jobID, err)
	}
	if stored != nil {
		if err := validateStoredResult(*stored); err != nil {
			return StoredResult{}, fmt.Errorf("pipeline: stored result for job %s is unusable: %w", jobID, err)
		}
		if err := a.signalStarted(ctx, stored.Engine); err != nil {
			return StoredResult{}, fmt.Errorf("pipeline: resignal started for job %s: %w", jobID, err)
		}
		return *stored, nil
	}

	workspace, err := os.MkdirTemp(a.workRoot, "packing-"+jobID+"-")
	if err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: create workspace for job %s: %w", jobID, err)
	}
	defer os.RemoveAll(workspace)

	envelope, err := a.fetchRequest(ctx, jobID, workspace)
	if err != nil {
		return StoredResult{}, err
	}

	specPath := filepath.Join(workspace, specFileName)
	if err := os.WriteFile(specPath, envelope.Spec, 0o600); err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: write packer input for job %s: %w", jobID, err)
	}

	engine, err := a.engine.Provenance(ctx)
	if err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: read packer provenance for job %s: %w", jobID, err)
	}
	if err := validateProvenance(engine); err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: packer provenance for job %s is unusable: %w", jobID, err)
	}

	if err := a.signalStarted(ctx, engine); err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: signal started for job %s: %w", jobID, err)
	}

	outputPath := filepath.Join(workspace, outputFileName)
	result, err := a.engine.Run(ctx, RunRequest{
		SpecPath:   specPath,
		OutputPath: outputPath,
		Runtime:    time.Duration(envelope.MaxRuntimeSeconds) * time.Second,
	})
	if err != nil {
		var failure *EngineFailure
		if errors.As(err, &failure) && ctx.Err() == nil {
			return StoredResult{}, engineFailure(engine, failure.Reason)
		}
		return StoredResult{}, fmt.Errorf("pipeline: run packer for job %s: %w", jobID, err)
	}

	created, err := a.artifacts.CreateResult(ctx, jobID, outputPath, StoredResult{Engine: engine, Result: result})
	if err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: upload result for job %s: %w", jobID, err)
	}
	if created == nil {
		return StoredResult{}, fmt.Errorf("pipeline: upload result for job %s: no metadata reported", jobID)
	}
	if err := validateStoredResult(*created); err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: stored result for job %s is unusable: %w", jobID, err)
	}
	return *created, nil
}

func (a *Activity) signalStarted(ctx context.Context, engine EngineProvenance) error {
	execution := activity.GetInfo(ctx).WorkflowExecution
	return a.signaler.SignalWorkflow(ctx, execution.ID, execution.RunID, contracts.EngineStartedSignal, contracts.EngineStarted{
		ContractVersion: contracts.ActivityVersion,
		EngineVersion:   engine.Version,
		EngineChecksum:  engine.Checksum,
	})
}

func (a *Activity) startHeartbeat(ctx context.Context) func() {
	heartbeatCtx, stop := context.WithCancel(ctx)
	done := make(chan struct{})
	ticker := time.NewTicker(a.heartbeatInterval)
	go func() {
		defer close(done)
		defer ticker.Stop()
		for {
			select {
			case <-heartbeatCtx.Done():
				return
			case <-ticker.C:
				activity.RecordHeartbeat(ctx)
			}
		}
	}()
	return func() {
		stop()
		<-done
	}
}

func (a *Activity) fetchRequest(ctx context.Context, jobID, workspace string) (contracts.RequestEnvelope, error) {
	path := filepath.Join(workspace, requestFileName)
	if err := a.artifacts.DownloadRequest(ctx, jobID, path); err != nil {
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

func engineFailure(engine EngineProvenance, reason string) error {
	reason = stripControlCharacters(reason)
	if reason == "" {
		reason = unreportedFailure
	}
	return temporal.NewNonRetryableApplicationError(reason, contracts.EngineFailureType, nil, contracts.EngineFailureDetails{
		ContractVersion: contracts.ActivityVersion,
		EngineVersion:   engine.Version,
		EngineChecksum:  engine.Checksum,
		Reason:          reason,
	})
}

func toEngineResult(stored StoredResult) contracts.EngineResult {
	return contracts.EngineResult{
		ContractVersion:   contracts.ActivityVersion,
		EngineVersion:     stored.Engine.Version,
		EngineChecksum:    stored.Engine.Checksum,
		ResultFileName:    stored.Result.FileName,
		ResultContentType: stored.Result.ContentType,
		ResultSizeBytes:   stored.Result.SizeBytes,
		ResultChecksum:    stored.Result.Checksum,
	}
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
