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

// The six metadata names a result blob carries. Five of them are read back by
// the Java side's AzurePackingJobArtifactStore.findResult, which fails the
// download endpoint when one is missing, so they are a cross-language contract
// exactly like the dispatch payload: renaming one here breaks the pipeline with
// no compiler and no unit test on either side objecting. Java's lookup is
// case-insensitive, and so is resultMetadataValue below, because Azure returns
// metadata keys with HTTP header canonicalisation already applied.
//
// contractVersion is the one name Java does not read. It is written so a result
// blob says which wire contract produced it, the same way every message on the
// packing wire carries contracts.MessageVersion.
const (
	metaFileName        = "fileName"
	metaContentType     = "contentType"
	metaChecksum        = "checksumSha256"
	metaEngineVersion   = "engineVersion"
	metaEngineChecksum  = "engineChecksumSha256"
	metaContractVersion = "contractVersion"
)

// ErrResultVanished reports the one outcome a conditional create must never
// paper over: the service refused the upload because a result already existed,
// and the lookup that followed found none. Returning the caller's own metadata
// there would claim authorship of bytes that are not in storage, and
// synthesising a result would describe a blob nobody can download.
var ErrResultVanished = errors.New("azureio: result conflicted on create but is not stored")

// requestKey and resultKey are the layout the Java side writes and reads —
// AzurePackingJobArtifactStore has the same two expressions. They take the job
// id from the decoded dispatch payload and nothing else: a key derived from a
// listing, a prefix scan or a message property would let a caller name a blob
// belonging to another job.
func requestKey(jobID string) string { return "packing-jobs/" + jobID + "/request.json" }

func resultKey(jobID string) string { return "packing-jobs/" + jobID + "/result/output" }

// BlobArtifacts stores one job's request and result under the container the
// Java side already writes into. It never creates that container: the request
// blob has to exist for a dispatch to have been sent at all, so a missing
// container is a misconfiguration to surface rather than to repair.
type BlobArtifacts struct {
	container *container.Client
}

var _ pipeline.Artifacts = (*BlobArtifacts)(nil)

// NewBlobClient picks the one authentication mode config.Load left set, the
// same way NewServiceBusClient does. config.Load already guarantees exactly one
// of the two is present, so neither is re-checked here.
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

// DownloadRequest streams the request envelope to path. The bytes go straight
// to disk because the caller re-reads them from there, and because a packing
// spec is only as small as whoever submitted it.
func (a *BlobArtifacts) DownloadRequest(ctx context.Context, jobID, path string) (err error) {
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("azureio: create request file for job %s: %w", jobID, err)
	}
	defer func() {
		// Joined rather than discarded: the caller decodes this file, and a
		// write failure that only surfaces at close would otherwise hand the
		// decoder a truncated envelope and read as a malformed request.
		if closeErr := file.Close(); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("azureio: close request file for job %s: %w", jobID, closeErr))
		}
	}()

	if _, downloadErr := a.container.NewBlobClient(requestKey(jobID)).DownloadFile(ctx, file, nil); downloadErr != nil {
		return fmt.Errorf("azureio: download request for job %s: %w", jobID, downloadErr)
	}
	return nil
}

// FindResult reads the result blob's metadata and reports (nil, nil) when there
// is none. Only a 404 is absence — a missing container included, which is what
// the Java side treats as absence too, since both are "no result for this job"
// and neither is a transport failure worth a redelivery.
//
// What it finds is returned as it stands, including a blank a writer left out.
// The processor validates every field against the Java domain model's rules and
// refuses to send an event built from a bad one; a second check here would be a
// second place for those rules to drift.
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
	// Size comes from the blob itself, never from metadata: it is the length of
	// the bytes a download will actually serve, and the Java side reports the
	// same number from the same place.
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

// CreateResult uploads path as the job's one result, and loses gracefully.
//
// The upload carries If-None-Match: *, so two deliveries of the same dispatch
// cannot both write: the second is refused, and reports what the first stored
// rather than what it computed itself. The two are not interchangeable — the
// bytes a user downloads are the winner's, and so is the provenance that has to
// describe them.
func (a *BlobArtifacts) CreateResult(ctx context.Context, jobID, path string, result pipeline.StoredResult) (*pipeline.StoredResult, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("azureio: open result file for job %s: %w", jobID, err)
	}
	// Read-only, so nothing is lost by ignoring the close error, and it must
	// not mask the upload's.
	defer func() { _ = file.Close() }()

	// blockblob.Upload, not the Client.UploadFile helper: UploadFile switches to
	// staged blocks above 256 MiB and its getCommitBlockListOptions drops
	// AccessConditions, so a large result would silently overwrite one already
	// in storage. A single conditional PUT carries the condition whatever the
	// size, and still streams the body off disk rather than buffering it — the
	// service's own Put Blob ceiling is a loud failure, which a lost condition
	// is not.
	_, err = a.container.NewBlockBlobClient(resultKey(jobID)).Upload(ctx, file, &blockblob.UploadOptions{
		Metadata: resultMetadata(result),
		// Set on the blob as well as in metadata so the SAS download URL the
		// Java side issues serves the result with the type the packer named.
		HTTPHeaders: &blob.HTTPHeaders{BlobContentType: to.Ptr(result.Result.ContentType)},
		AccessConditions: &blob.AccessConditions{
			ModifiedAccessConditions: &blob.ModifiedAccessConditions{IfNoneMatch: to.Ptr(azcore.ETagAny)},
		},
	})
	if err == nil {
		return &result, nil
	}
	// Both codes mean the same thing here. The service answers a rejected
	// If-None-Match: * with BlobAlreadyExists on a fresh blob and
	// ConditionNotMet on some paths; the Java side accepts 409 and 412 for the
	// same reason.
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

// resultMetadataValue matches names without regard to case. Azure sends
// metadata as x-ms-meta-* response headers, and net/http canonicalises a header
// name on the way in, so the map this reads has "Filename" where the writer
// sent "fileName". The Java side compares case-insensitively for the same
// reason, which is what lets either side write the name in its own casing.
func resultMetadataValue(metadata map[string]*string, name string) string {
	for key, value := range metadata {
		if strings.EqualFold(key, name) && value != nil {
			return *value
		}
	}
	return ""
}
