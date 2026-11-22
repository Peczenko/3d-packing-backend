package azureio

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"

	"github.com/Azure/azure-sdk-for-go/sdk/azcore"
	"github.com/Azure/azure-sdk-for-go/sdk/azcore/streaming"
	"github.com/Azure/azure-sdk-for-go/sdk/azcore/to"
	"github.com/Azure/azure-sdk-for-go/sdk/azidentity"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/blob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/bloberror"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/blockblob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/container"
	"golang.org/x/sync/errgroup"

	"github.com/Peczenko/3d-packing-backend/worker/internal/config"
	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

const (
	metaFileName        = "fileName"
	metaContentType     = "contentType"
	metaChecksum        = "checksumSha256"
	metaEngineVersion   = "engineVersion"
	metaEngineChecksum  = "engineChecksumSha256"
	metaContractVersion = "contractVersion"
	metaBinCount        = "binCount"
	metaSizeBytes       = "sizeBytes"

	blockSize          = 8 << 20
	blockConcurrency   = 4
	maxPlacementsBytes = 4 << 20
)

var (
	ErrResultVanished     = errors.New("azureio: result conflicted on create but is not stored")
	ErrPlacementsVanished = errors.New("azureio: placements conflicted on create but are not stored")
)

func requestKey(jobID string) string { return "packing-jobs/" + jobID + "/request.json" }

func resultKey(jobID string) string { return "packing-jobs/" + jobID + "/result/output" }

func placementsKey(jobID string) string { return "packing-jobs/" + jobID + "/result/placements.json" }

func binKey(jobID, resultChecksum string, number int) string {
	return "packing-jobs/" + jobID + "/result/bins/" + resultChecksum + "/" + strconv.Itoa(number)
}

type BlobArtifacts struct {
	container *container.Client
}

var _ pipeline.Artifacts = (*BlobArtifacts)(nil)

func NewBlobClient(cfg config.Config) (*azblob.Client, error) {
	if cfg.StorageConnectionString != "" {
		client, err := azblob.NewClientFromConnectionString(cfg.StorageConnectionString, nil)
		if err != nil {
			// The connection string carries the account key and is never
			// interpolated into an error or a log line.
			return nil, fmt.Errorf("azureio: build blob client from connection string: %w", err)
		}
		return client, nil
	}

	credential, err := azidentity.NewDefaultAzureCredential(nil)
	if err != nil {
		return nil, fmt.Errorf("azureio: build default azure credential: %w", err)
	}
	client, err := azblob.NewClient(cfg.StorageAccountURL, credential, nil)
	if err != nil {
		return nil, fmt.Errorf("azureio: build blob client for account %s: %w", cfg.StorageAccountURL, err)
	}
	return client, nil
}

func NewBlobArtifacts(client *azblob.Client, cfg config.Config) *BlobArtifacts {
	return &BlobArtifacts{container: client.ServiceClient().NewContainerClient(cfg.StorageContainer)}
}

func (a *BlobArtifacts) DownloadRequest(ctx context.Context, jobID, path string) error {
	if err := a.download(ctx, requestKey(jobID), path); err != nil {
		return fmt.Errorf("azureio: download request for job %s: %w", jobID, err)
	}
	return nil
}

func (a *BlobArtifacts) DownloadInput(ctx context.Context, storageKey, path string) error {
	err := a.download(ctx, storageKey, path)
	switch {
	case err == nil:
		return nil
	case bloberror.HasCode(err, bloberror.BlobNotFound, bloberror.ContainerNotFound):
		return fmt.Errorf("azureio: input %s: %w", storageKey, pipeline.ErrInputNotFound)
	default:
		return fmt.Errorf("azureio: download input %s: %w", storageKey, err)
	}
}

func (a *BlobArtifacts) download(ctx context.Context, key, path string) (err error) {
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("create %s: %w", path, err)
	}
	defer func() {
		if closeErr := file.Close(); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("close %s: %w", path, closeErr))
		}
	}()

	_, err = a.container.NewBlobClient(key).DownloadFile(ctx, file, nil)
	return err
}

func (a *BlobArtifacts) FindPlacements(ctx context.Context, jobID string) (*pipeline.StoredPlacements, error) {
	response, err := a.container.NewBlobClient(placementsKey(jobID)).DownloadStream(ctx, nil)
	if err != nil {
		if bloberror.HasCode(err, bloberror.BlobNotFound, bloberror.ContainerNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("azureio: download placements for job %s: %w", jobID, err)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxPlacementsBytes+1))
	if closeErr := response.Body.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return nil, fmt.Errorf("azureio: read placements for job %s: %w", jobID, err)
	}
	if len(body) > maxPlacementsBytes {
		return nil, fmt.Errorf("azureio: placements for job %s exceed %d bytes", jobID, maxPlacementsBytes)
	}

	var wire contracts.ResultPlacements
	if err := json.Unmarshal(body, &wire); err != nil {
		return nil, fmt.Errorf("azureio: decode placements for job %s: %w", jobID, err)
	}
	if wire.Version != contracts.PlacementsVersion {
		return nil, fmt.Errorf("azureio: placements for job %s are version %d, want %d", jobID, wire.Version, contracts.PlacementsVersion)
	}
	layout := pipeline.Layout{BinOffsets: wire.BinOffsets, Placements: make([]pipeline.Placement, 0, len(wire.Placements))}
	for _, placement := range wire.Placements {
		layout.Placements = append(layout.Placements, pipeline.Placement{Item: placement.Item, Bin: placement.Bin, Matrix: placement.Matrix})
	}
	return &pipeline.StoredPlacements{
		Engine: pipeline.EngineProvenance{
			Version:  resultMetadataValue(response.Metadata, metaEngineVersion),
			Checksum: resultMetadataValue(response.Metadata, metaEngineChecksum),
		},
		Layout: layout,
	}, nil
}

func (a *BlobArtifacts) CreatePlacements(ctx context.Context, jobID string, placements pipeline.StoredPlacements) (*pipeline.StoredPlacements, error) {
	wire := contracts.ResultPlacements{
		Version:    contracts.PlacementsVersion,
		BinOffsets: placements.Layout.BinOffsets,
		Placements: make([]contracts.ResultPlacement, 0, len(placements.Layout.Placements)),
	}
	for _, placement := range placements.Layout.Placements {
		wire.Placements = append(wire.Placements, contracts.ResultPlacement{Item: placement.Item, Bin: placement.Bin, Matrix: placement.Matrix})
	}
	body, err := json.Marshal(wire)
	if err != nil {
		return nil, fmt.Errorf("azureio: encode placements for job %s: %w", jobID, err)
	}

	_, err = a.container.NewBlockBlobClient(placementsKey(jobID)).Upload(ctx, streaming.NopCloser(bytes.NewReader(body)), &blockblob.UploadOptions{
		Metadata: map[string]*string{
			metaEngineVersion:   to.Ptr(placements.Engine.Version),
			metaEngineChecksum:  to.Ptr(placements.Engine.Checksum),
			metaContractVersion: to.Ptr(strconv.Itoa(contracts.ActivityVersion)),
			metaBinCount:        to.Ptr(strconv.Itoa(len(placements.Layout.BinOffsets))),
		},
		HTTPHeaders:      &blob.HTTPHeaders{BlobContentType: to.Ptr("application/json")},
		AccessConditions: ifAbsent(),
	})
	if err == nil {
		return &placements, nil
	}
	if !bloberror.HasCode(err, bloberror.BlobAlreadyExists, bloberror.ConditionNotMet) {
		return nil, fmt.Errorf("azureio: upload placements for job %s: %w", jobID, err)
	}

	existing, findErr := a.FindPlacements(ctx, jobID)
	if findErr != nil {
		return nil, fmt.Errorf("azureio: read winning placements for job %s: %w", jobID, findErr)
	}
	if existing == nil {
		return nil, fmt.Errorf("%w: job %s", ErrPlacementsVanished, jobID)
	}
	return existing, nil
}

func ifAbsent() *blob.AccessConditions {
	return &blob.AccessConditions{
		ModifiedAccessConditions: &blob.ModifiedAccessConditions{IfNoneMatch: to.Ptr(azcore.ETagAny)},
	}
}

func (a *BlobArtifacts) FindResult(ctx context.Context, jobID string) (*pipeline.StoredResult, error) {
	properties, err := a.container.NewBlobClient(resultKey(jobID)).GetProperties(ctx, nil)
	if err != nil {
		if bloberror.HasCode(err, bloberror.BlobNotFound, bloberror.ContainerNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("azureio: read result metadata for job %s: %w", jobID, err)
	}

	size := decodedSize(properties.Metadata, properties.ContentLength)
	stored := &pipeline.StoredResult{
		Engine: pipeline.EngineProvenance{
			Version:  resultMetadataValue(properties.Metadata, metaEngineVersion),
			Checksum: resultMetadataValue(properties.Metadata, metaEngineChecksum),
		},
		Result: pipeline.ResultFiles{
			FileName:        resultMetadataValue(properties.Metadata, metaFileName),
			ContentType:     resultMetadataValue(properties.Metadata, metaContentType),
			ContentEncoding: valueOf(properties.ContentEncoding),
			SizeBytes:       size,
			Checksum:        resultMetadataValue(properties.Metadata, metaChecksum),
		},
	}
	bins, err := a.findBins(ctx, jobID, stored.Result, resultMetadataValue(properties.Metadata, metaBinCount))
	if err != nil {
		return nil, err
	}
	stored.Result.Bins = bins
	return stored, nil
}

func (a *BlobArtifacts) findBins(ctx context.Context, jobID string, result pipeline.ResultFiles, recorded string) ([]pipeline.ResultBin, error) {
	count, err := strconv.Atoi(recorded)
	if err != nil || count < 1 || count > contracts.MaxTotalCopies {
		return nil, nil
	}
	if count == 1 {
		return []pipeline.ResultBin{{SizeBytes: result.SizeBytes, Checksum: result.Checksum}}, nil
	}
	bins := make([]pipeline.ResultBin, 0, count)
	for number := 1; number <= count; number++ {
		properties, err := a.container.NewBlobClient(binKey(jobID, result.Checksum, number)).GetProperties(ctx, nil)
		if err != nil {
			return nil, fmt.Errorf("azureio: read bin %d of the result for job %s: %w", number, jobID, err)
		}
		bins = append(bins, pipeline.ResultBin{
			SizeBytes: decodedSize(properties.Metadata, properties.ContentLength),
			Checksum:  resultMetadataValue(properties.Metadata, metaChecksum),
		})
	}
	return bins, nil
}

func (a *BlobArtifacts) CreateResult(ctx context.Context, jobID string, result pipeline.StoredResult) (*pipeline.StoredResult, error) {
	if result.Result.Path == "" {
		return nil, fmt.Errorf("azureio: the result for job %s names no file to upload", jobID)
	}
	if len(result.Result.Bins) > 1 {
		for i, bin := range result.Result.Bins {
			if err := a.uploadBin(ctx, jobID, result.Result, i+1, bin); err != nil {
				return nil, err
			}
		}
	}

	err := a.uploadFile(ctx, resultKey(jobID), result.Result.Path, &blockblob.CommitBlockListOptions{
		Metadata:         resultMetadata(result),
		HTTPHeaders:      resultHeaders(result.Result),
		AccessConditions: ifAbsent(),
	})
	if err == nil {
		return storedForm(result), nil
	}
	if !bloberror.HasCode(err, bloberror.BlobAlreadyExists, bloberror.ConditionNotMet, bloberror.InvalidBlockList) {
		return nil, fmt.Errorf("azureio: upload result for job %s: %w", jobID, err)
	}

	existing, findErr := a.FindResult(ctx, jobID)
	if findErr != nil {
		return nil, fmt.Errorf("azureio: read winning result for job %s: %w", jobID, findErr)
	}
	if existing == nil {
		if bloberror.HasCode(err, bloberror.InvalidBlockList) {
			return nil, fmt.Errorf("azureio: upload result for job %s: %w", jobID, err)
		}
		return nil, fmt.Errorf("%w: job %s", ErrResultVanished, jobID)
	}
	return existing, nil
}

func (a *BlobArtifacts) uploadFile(ctx context.Context, key, path string, options *blockblob.CommitBlockListOptions) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer func() { _ = file.Close() }()
	info, err := file.Stat()
	if err != nil {
		return err
	}

	prefix := make([]byte, 16)
	if _, err := rand.Read(prefix); err != nil {
		return err
	}
	client := a.container.NewBlockBlobClient(key)
	size := info.Size()
	ids := make([]string, (size+blockSize-1)/blockSize)
	group, groupCtx := errgroup.WithContext(ctx)
	group.SetLimit(blockConcurrency)
	for i := range ids {
		id := binary.BigEndian.AppendUint32(append([]byte(nil), prefix...), uint32(i))
		ids[i] = base64.StdEncoding.EncodeToString(id)
		offset := int64(i) * blockSize
		block := io.NewSectionReader(file, offset, min(blockSize, size-offset))
		group.Go(func() error {
			_, err := client.StageBlock(groupCtx, ids[i], streaming.NopCloser(block), nil)
			return err
		})
	}
	if err := group.Wait(); err != nil {
		return err
	}
	_, err = client.CommitBlockList(ctx, ids, options)
	return err
}

func resultHeaders(result pipeline.ResultFiles) *blob.HTTPHeaders {
	headers := &blob.HTTPHeaders{BlobContentType: to.Ptr(result.ContentType)}
	if result.ContentEncoding != "" {
		headers.BlobContentEncoding = to.Ptr(result.ContentEncoding)
	}
	return headers
}

func valueOf(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

func decodedSize(metadata map[string]*string, contentLength *int64) int64 {
	if size, err := strconv.ParseInt(resultMetadataValue(metadata, metaSizeBytes), 10, 64); err == nil && size > 0 {
		return size
	}
	if contentLength != nil {
		return *contentLength
	}
	return 0
}

func storedForm(result pipeline.StoredResult) *pipeline.StoredResult {
	bins := make([]pipeline.ResultBin, len(result.Result.Bins))
	for i, bin := range result.Result.Bins {
		bins[i] = pipeline.ResultBin{SizeBytes: bin.SizeBytes, Checksum: bin.Checksum}
	}
	result.Result.Bins = bins
	result.Result.Path = ""
	result.Result.StoredBytes = 0
	return &result
}

func (a *BlobArtifacts) uploadBin(ctx context.Context, jobID string, result pipeline.ResultFiles, number int, bin pipeline.ResultBin) error {
	key := binKey(jobID, result.Checksum, number)
	err := a.uploadFile(ctx, key, bin.Path, &blockblob.CommitBlockListOptions{
		Metadata: map[string]*string{
			metaChecksum:  to.Ptr(bin.Checksum),
			metaSizeBytes: to.Ptr(strconv.FormatInt(bin.SizeBytes, 10)),
		},
		HTTPHeaders:      resultHeaders(result),
		AccessConditions: ifAbsent(),
	})
	if err == nil {
		return nil
	}
	if bloberror.HasCode(err, bloberror.BlobAlreadyExists, bloberror.ConditionNotMet, bloberror.InvalidBlockList) {
		properties, findErr := a.container.NewBlobClient(key).GetProperties(ctx, nil)
		if findErr == nil && resultMetadataValue(properties.Metadata, metaChecksum) == bin.Checksum {
			return nil
		}
	}
	return fmt.Errorf("azureio: upload bin %d for job %s: %w", number, jobID, err)
}

func resultMetadata(result pipeline.StoredResult) map[string]*string {
	return map[string]*string{
		metaFileName:        to.Ptr(result.Result.FileName),
		metaContentType:     to.Ptr(result.Result.ContentType),
		metaChecksum:        to.Ptr(result.Result.Checksum),
		metaEngineVersion:   to.Ptr(result.Engine.Version),
		metaEngineChecksum:  to.Ptr(result.Engine.Checksum),
		metaContractVersion: to.Ptr(strconv.Itoa(contracts.ActivityVersion)),
		metaBinCount:        to.Ptr(strconv.Itoa(len(result.Result.Bins))),
		metaSizeBytes:       to.Ptr(strconv.FormatInt(result.Result.SizeBytes, 10)),
	}
}

func resultMetadataValue(metadata map[string]*string, name string) string {
	for key, value := range metadata {
		if strings.EqualFold(key, name) && value != nil {
			return *value
		}
	}
	return ""
}
