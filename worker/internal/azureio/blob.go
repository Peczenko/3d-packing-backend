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
)

var ErrResultVanished = errors.New("azureio: result conflicted on create but is not stored")

func requestKey(jobID string) string { return "packing-jobs/" + jobID + "/request.json" }

func resultKey(jobID string) string { return "packing-jobs/" + jobID + "/result/output" }

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

func (a *BlobArtifacts) DownloadRequest(ctx context.Context, jobID, path string) (err error) {
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("azureio: create request file for job %s: %w", jobID, err)
	}
	defer func() {
		if closeErr := file.Close(); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("azureio: close request file for job %s: %w", jobID, closeErr))
		}
	}()

	if _, downloadErr := a.container.NewBlobClient(requestKey(jobID)).DownloadFile(ctx, file, nil); downloadErr != nil {
		return fmt.Errorf("azureio: download request for job %s: %w", jobID, downloadErr)
	}
	return nil
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

	return &pipeline.StoredResult{
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
	}, nil
}

func (a *BlobArtifacts) CreateResult(ctx context.Context, jobID, path string, result pipeline.StoredResult) (*pipeline.StoredResult, error) {
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
		return &result, nil
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

func resultMetadata(result pipeline.StoredResult) map[string]*string {
	return map[string]*string{
		metaFileName:        to.Ptr(result.Result.FileName),
		metaContentType:     to.Ptr(result.Result.ContentType),
		metaChecksum:        to.Ptr(result.Result.Checksum),
		metaEngineVersion:   to.Ptr(result.Engine.Version),
		metaEngineChecksum:  to.Ptr(result.Engine.Checksum),
		metaContractVersion: to.Ptr(strconv.Itoa(contracts.MessageVersion)),
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
