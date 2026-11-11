package azureio

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/Azure/azure-sdk-for-go/sdk/azcore"
	"github.com/Azure/azure-sdk-for-go/sdk/azcore/to"
	"github.com/Azure/azure-sdk-for-go/sdk/azidentity"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/blob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/bloberror"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/blockblob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/container"

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
)

var ErrResultVanished = errors.New("azureio: result conflicted on create but is not stored")

func requestKey(jobID string) string { return "packing-jobs/" + jobID + "/request.json" }

func resultKey(jobID string) string { return "packing-jobs/" + jobID + "/result/output" }

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

func (a *BlobArtifacts) FindResult(ctx context.Context, jobID string) (*pipeline.StoredResult, error) {
	properties, err := a.container.NewBlobClient(resultKey(jobID)).GetProperties(ctx, nil)
	if err != nil {
		if bloberror.HasCode(err, bloberror.BlobNotFound, bloberror.ContainerNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("azureio: read result metadata for job %s: %w", jobID, err)
	}

	var size int64
	if properties.ContentLength != nil {
		size = *properties.ContentLength
	}

	stored := &pipeline.StoredResult{
		Engine: pipeline.EngineProvenance{
			Version:  resultMetadataValue(properties.Metadata, metaEngineVersion),
			Checksum: resultMetadataValue(properties.Metadata, metaEngineChecksum),
		},
		Result: pipeline.EngineResult{
			FileName:    resultMetadataValue(properties.Metadata, metaFileName),
			ContentType: resultMetadataValue(properties.Metadata, metaContentType),
			SizeBytes:   size,
			Checksum:    resultMetadataValue(properties.Metadata, metaChecksum),
		},
	}
	bins, err := a.findBins(ctx, jobID, stored.Result, resultMetadataValue(properties.Metadata, metaBinCount))
	if err != nil {
		return nil, err
	}
	stored.Result.Bins = bins
	return stored, nil
}

func (a *BlobArtifacts) findBins(ctx context.Context, jobID string, result pipeline.EngineResult, recorded string) ([]pipeline.ResultBin, error) {
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
		bin := pipeline.ResultBin{Checksum: resultMetadataValue(properties.Metadata, metaChecksum)}
		if properties.ContentLength != nil {
			bin.SizeBytes = *properties.ContentLength
		}
		bins = append(bins, bin)
	}
	return bins, nil
}

func (a *BlobArtifacts) CreateResult(ctx context.Context, jobID, path string, result pipeline.StoredResult) (*pipeline.StoredResult, error) {
	if len(result.Result.Bins) > 1 {
		for i, bin := range result.Result.Bins {
			if err := a.uploadBin(ctx, jobID, result.Result, i+1, bin); err != nil {
				return nil, err
			}
		}
	}

	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("azureio: open result file for job %s: %w", jobID, err)
	}
	defer func() { _ = file.Close() }()

	_, err = a.container.NewBlockBlobClient(resultKey(jobID)).Upload(ctx, file, &blockblob.UploadOptions{
		Metadata:    resultMetadata(result),
		HTTPHeaders: &blob.HTTPHeaders{BlobContentType: to.Ptr(result.Result.ContentType)},
		AccessConditions: &blob.AccessConditions{
			ModifiedAccessConditions: &blob.ModifiedAccessConditions{IfNoneMatch: to.Ptr(azcore.ETagAny)},
		},
	})
	if err == nil {
		return storedForm(result), nil
	}
	if !bloberror.HasCode(err, bloberror.BlobAlreadyExists, bloberror.ConditionNotMet) {
		return nil, fmt.Errorf("azureio: upload result for job %s: %w", jobID, err)
	}

	existing, findErr := a.FindResult(ctx, jobID)
	if findErr != nil {
		return nil, fmt.Errorf("azureio: read winning result for job %s: %w", jobID, findErr)
	}
	if existing == nil {
		return nil, fmt.Errorf("%w: job %s", ErrResultVanished, jobID)
	}
	return existing, nil
}

func storedForm(result pipeline.StoredResult) *pipeline.StoredResult {
	bins := make([]pipeline.ResultBin, len(result.Result.Bins))
	for i, bin := range result.Result.Bins {
		bins[i] = pipeline.ResultBin{SizeBytes: bin.SizeBytes, Checksum: bin.Checksum}
	}
	result.Result.Bins = bins
	return &result
}

func (a *BlobArtifacts) uploadBin(ctx context.Context, jobID string, result pipeline.EngineResult, number int, bin pipeline.ResultBin) error {
	file, err := os.Open(bin.Path)
	if err != nil {
		return fmt.Errorf("azureio: open bin %d for job %s: %w", number, jobID, err)
	}
	defer func() { _ = file.Close() }()

	_, err = a.container.NewBlockBlobClient(binKey(jobID, result.Checksum, number)).Upload(ctx, file, &blockblob.UploadOptions{
		Metadata:    map[string]*string{metaChecksum: to.Ptr(bin.Checksum)},
		HTTPHeaders: &blob.HTTPHeaders{BlobContentType: to.Ptr(result.ContentType)},
	})
	if err != nil {
		return fmt.Errorf("azureio: upload bin %d for job %s: %w", number, jobID, err)
	}
	return nil
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
