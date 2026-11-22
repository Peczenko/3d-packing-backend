package pipeline

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/temporal"

	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
	"github.com/Peczenko/3d-packing-backend/worker/internal/digest"
)

const (
	requestFileName       = "request.json"
	inputsDirName         = "inputs"
	unreportedFailure     = "packer failed without reporting a reason"
	unwritableResult      = "the result could not be written"
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
	writer            ResultWriter
	signaler          Signaler
	workRoot          string
	heartbeatInterval time.Duration
}

func NewActivity(artifacts Artifacts, engine Engine, writer ResultWriter, signaler Signaler, workRoot string, heartbeatInterval time.Duration) *Activity {
	return &Activity{
		artifacts:         artifacts,
		engine:            engine,
		writer:            writer,
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
		return contracts.EngineResult{}, invalidInput(err)
	}

	stopHeartbeat := a.startHeartbeat(ctx)
	defer stopHeartbeat()

	stored, err := a.pack(ctx, in.JobID)
	if err != nil {
		return contracts.EngineResult{}, err
	}
	return toEngineResult(stored), nil
}

func (a *Activity) WriteResult(ctx context.Context, input contracts.WriteResultInput) (contracts.ResultFiles, error) {
	if a.heartbeatInterval <= 0 {
		return contracts.ResultFiles{}, fmt.Errorf("pipeline: heartbeat interval must be positive, got %s", a.heartbeatInterval)
	}
	in, err := contracts.ValidateWriteResultInput(input)
	if err != nil {
		return contracts.ResultFiles{}, invalidInput(err)
	}

	stopHeartbeat := a.startHeartbeat(ctx)
	defer stopHeartbeat()

	stored, err := a.write(ctx, in.JobID)
	if err != nil {
		return contracts.ResultFiles{}, err
	}
	return toResultFiles(stored), nil
}

func (a *Activity) pack(ctx context.Context, jobID string) (StoredPlacements, error) {
	stored, err := a.artifacts.FindPlacements(ctx, jobID)
	if err != nil {
		return StoredPlacements{}, fmt.Errorf("pipeline: look up placements for job %s: %w", jobID, err)
	}
	if stored != nil {
		if err := validateStoredPlacements(*stored); err != nil {
			return StoredPlacements{}, fmt.Errorf("pipeline: stored placements for job %s are unusable: %w", jobID, err)
		}
		if err := a.signalStarted(ctx, stored.Engine); err != nil {
			return StoredPlacements{}, fmt.Errorf("pipeline: resignal started for job %s: %w", jobID, err)
		}
		return *stored, nil
	}

	workspace, err := os.MkdirTemp(a.workRoot, "packing-"+jobID+"-")
	if err != nil {
		return StoredPlacements{}, fmt.Errorf("pipeline: create workspace for job %s: %w", jobID, err)
	}
	defer os.RemoveAll(workspace)

	envelope, err := a.fetchRequest(ctx, jobID, workspace)
	if err != nil {
		return StoredPlacements{}, err
	}

	engine, err := a.engine.Provenance(ctx)
	if err != nil {
		return StoredPlacements{}, fmt.Errorf("pipeline: read packer provenance for job %s: %w", jobID, err)
	}
	if err := validateProvenance(engine); err != nil {
		return StoredPlacements{}, fmt.Errorf("pipeline: packer provenance for job %s is unusable: %w", jobID, err)
	}

	log := activity.GetLogger(ctx)
	phase := time.Now()
	inputs, err := a.downloadInputs(ctx, jobID, workspace, envelope.Items)
	log.Info("inputs downloaded", "jobId", jobID, "items", len(envelope.Items), "elapsed", time.Since(phase).String(), "error", err)
	if err != nil {
		var failure *EngineFailure
		if errors.As(err, &failure) {
			return StoredPlacements{}, engineFailure(engine, failure.Reason)
		}
		return StoredPlacements{}, err
	}

	if err := a.signalStarted(ctx, engine); err != nil {
		return StoredPlacements{}, fmt.Errorf("pipeline: signal started for job %s: %w", jobID, err)
	}

	phase = time.Now()
	packing, err := a.engine.Run(ctx, RunRequest{
		Inputs:        inputs,
		ParameterArgs: envelope.ParameterArgs,
		WorkDir:       workspace,
		Runtime:       time.Duration(envelope.MaxRuntimeSeconds) * time.Second,
	})
	log.Info("packer finished", "jobId", jobID, "limitSeconds", envelope.MaxRuntimeSeconds, "elapsed", time.Since(phase).String(),
		"preparationSeconds", packing.PreparationSeconds, "searchSeconds", packing.SearchSeconds,
		"placements", len(packing.Layout.Placements), "bins", len(packing.Layout.BinOffsets), "error", err)
	if err != nil {
		var failure *EngineFailure
		if errors.As(err, &failure) && ctx.Err() == nil {
			return StoredPlacements{}, engineFailure(engine, failure.Reason)
		}
		return StoredPlacements{}, fmt.Errorf("pipeline: run packer for job %s: %w", jobID, err)
	}

	phase = time.Now()
	created, err := a.artifacts.CreatePlacements(ctx, jobID, StoredPlacements{Engine: engine, Layout: packing.Layout})
	log.Info("placements stored", "jobId", jobID, "elapsed", time.Since(phase).String(), "error", err)
	if err != nil {
		return StoredPlacements{}, fmt.Errorf("pipeline: store placements for job %s: %w", jobID, err)
	}
	if created == nil {
		return StoredPlacements{}, fmt.Errorf("pipeline: store placements for job %s: nothing reported", jobID)
	}
	if err := validateStoredPlacements(*created); err != nil {
		return StoredPlacements{}, fmt.Errorf("pipeline: stored placements for job %s are unusable: %w", jobID, err)
	}
	return *created, nil
}

func (a *Activity) write(ctx context.Context, jobID string) (StoredResult, error) {
	stored, err := a.artifacts.FindResult(ctx, jobID)
	if err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: look up result for job %s: %w", jobID, err)
	}
	if stored != nil {
		if err := validateStoredResult(*stored); err != nil {
			return StoredResult{}, fmt.Errorf("pipeline: stored result for job %s is unusable: %w", jobID, err)
		}
		return *stored, nil
	}

	placements, err := a.artifacts.FindPlacements(ctx, jobID)
	if err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: look up placements for job %s: %w", jobID, err)
	}
	if placements == nil {
		return StoredResult{}, fmt.Errorf("pipeline: job %s has no stored placements to write a result from", jobID)
	}
	if err := validateStoredPlacements(*placements); err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: stored placements for job %s are unusable: %w", jobID, err)
	}

	workspace, err := os.MkdirTemp(a.workRoot, "result-"+jobID+"-")
	if err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: create workspace for job %s: %w", jobID, err)
	}
	defer os.RemoveAll(workspace)

	envelope, err := a.fetchRequest(ctx, jobID, workspace)
	if err != nil {
		return StoredResult{}, err
	}

	log := activity.GetLogger(ctx)
	phase := time.Now()
	inputs, err := a.downloadInputs(ctx, jobID, workspace, envelope.Items)
	log.Info("inputs downloaded", "jobId", jobID, "items", len(envelope.Items), "elapsed", time.Since(phase).String(), "error", err)
	if err != nil {
		var failure *EngineFailure
		if errors.As(err, &failure) {
			return StoredResult{}, resultFailure(failure.Reason)
		}
		return StoredResult{}, err
	}
	if err := validateLayout(inputs, placements.Layout); err != nil {
		return StoredResult{}, fmt.Errorf("pipeline: placements for job %s do not match its request: %w", jobID, err)
	}

	phase = time.Now()
	result, err := a.writer.Write(ctx, inputs, placements.Layout, workspace)
	log.Info("result written", "jobId", jobID, "elapsed", time.Since(phase).String(), "resultBytes", result.SizeBytes, "storedBytes", result.StoredBytes, "error", err)
	if err != nil {
		var failure *EngineFailure
		if errors.As(err, &failure) && ctx.Err() == nil {
			return StoredResult{}, resultFailure(failure.Reason)
		}
		return StoredResult{}, fmt.Errorf("pipeline: write result for job %s: %w", jobID, err)
	}

	phase = time.Now()
	created, err := a.artifacts.CreateResult(ctx, jobID, StoredResult{Engine: placements.Engine, Result: result})
	log.Info("result uploaded", "jobId", jobID, "storedBytes", result.StoredBytes, "elapsed", time.Since(phase).String(), "error", err)
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
		return contracts.RequestEnvelope{}, invalidInput(fmt.Errorf("pipeline: job %s: %w", jobID, err))
	}
	return envelope, nil
}

func (a *Activity) downloadInputs(ctx context.Context, jobID, workspace string, items []contracts.RequestItem) ([]InputFile, error) {
	dir := filepath.Join(workspace, inputsDirName)
	if err := os.Mkdir(dir, 0o700); err != nil {
		return nil, fmt.Errorf("pipeline: create input directory for job %s: %w", jobID, err)
	}
	inputs := make([]InputFile, 0, len(items))
	for i, item := range items {
		path := filepath.Join(dir, strconv.Itoa(i)+"."+contracts.AcceptedFormats[item.Format])
		if err := a.artifacts.DownloadInput(ctx, item.StorageKey, path); err != nil {
			if errors.Is(err, ErrInputNotFound) {
				return nil, &EngineFailure{Reason: fmt.Sprintf("Input file '%s' is no longer available", item.FileName)}
			}
			return nil, fmt.Errorf("pipeline: download input %d for job %s: %w", i, jobID, err)
		}
		checksum, err := digest.File(path)
		if err != nil {
			return nil, fmt.Errorf("pipeline: checksum input %d for job %s: %w", i, jobID, err)
		}
		if checksum != item.Checksum {
			return nil, &EngineFailure{Reason: fmt.Sprintf("Input file '%s' does not match its recorded checksum", item.FileName)}
		}
		inputs = append(inputs, InputFile{Path: path, DisplayName: item.FileName, Copies: item.Copies})
	}
	return inputs, nil
}

func invalidInput(err error) error {
	return temporal.NewNonRetryableApplicationError(err.Error(), contracts.InvalidInputType, err)
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

func resultFailure(reason string) error {
	reason = stripControlCharacters(reason)
	if reason == "" {
		reason = unwritableResult
	}
	return temporal.NewNonRetryableApplicationError(reason, contracts.ResultFailureType, nil)
}

func toEngineResult(stored StoredPlacements) contracts.EngineResult {
	return contracts.EngineResult{
		ContractVersion: contracts.ActivityVersion,
		EngineVersion:   stored.Engine.Version,
		EngineChecksum:  stored.Engine.Checksum,
		BinCount:        len(stored.Layout.BinOffsets),
	}
}

func toResultFiles(stored StoredResult) contracts.ResultFiles {
	bins := make([]contracts.ResultBin, 0, len(stored.Result.Bins))
	for _, bin := range stored.Result.Bins {
		bins = append(bins, contracts.ResultBin{SizeBytes: bin.SizeBytes, Checksum: bin.Checksum})
	}
	return contracts.ResultFiles{
		ContractVersion:   contracts.ActivityVersion,
		ResultFileName:    stored.Result.FileName,
		ResultContentType: stored.Result.ContentType,
		ResultSizeBytes:   stored.Result.SizeBytes,
		ResultChecksum:    stored.Result.Checksum,
		ResultBins:        bins,
	}
}

func validateStoredPlacements(stored StoredPlacements) error {
	if err := validateProvenance(stored.Engine); err != nil {
		return err
	}
	bins := len(stored.Layout.BinOffsets)
	if bins == 0 {
		return errors.New("placements list no bins")
	}
	if len(stored.Layout.Placements) == 0 {
		return errors.New("placements place nothing")
	}
	filled := make([]bool, bins)
	for i, placement := range stored.Layout.Placements {
		if placement.Bin < 1 || placement.Bin > bins {
			return fmt.Errorf("placement %d names bin %d of %d", i+1, placement.Bin, bins)
		}
		filled[placement.Bin-1] = true
	}
	for i, ok := range filled {
		if !ok {
			return fmt.Errorf("bin %d holds no copy", i+1)
		}
	}
	return nil
}

func validateLayout(inputs []InputFile, layout Layout) error {
	want := 0
	for _, input := range inputs {
		want += input.Copies
	}
	if len(layout.Placements) != want {
		return fmt.Errorf("they place %d copies and the request has %d", len(layout.Placements), want)
	}
	next := 0
	for item, input := range inputs {
		for range input.Copies {
			if layout.Placements[next].Item != item {
				return fmt.Errorf("placement %d is not a copy of item %d", next+1, item)
			}
			next++
		}
	}
	return nil
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
	return validateBins(stored.Result)
}

func validateBins(result ResultFiles) error {
	if len(result.Bins) == 0 {
		return errors.New("result lists no bins")
	}
	for i, bin := range result.Bins {
		if bin.SizeBytes <= 0 {
			return fmt.Errorf("bin %d size %d is not positive", i+1, bin.SizeBytes)
		}
		if !sha256Hex.MatchString(bin.Checksum) {
			return fmt.Errorf("bin %d checksum %q is not a SHA-256 hex string", i+1, bin.Checksum)
		}
	}
	if only := result.Bins[0]; len(result.Bins) == 1 && (only.SizeBytes != result.SizeBytes || only.Checksum != result.Checksum) {
		return errors.New("a single bin is not the combined result")
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
