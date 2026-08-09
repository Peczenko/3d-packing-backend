package azureio

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/azcore/to"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/blob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/bloberror"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/container"

	"github.com/Peczenko/3d-packing-backend/worker/internal/config"
	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

// storageEnv is the variable config.Load reads for the local storage mode, so
// the same value that configures the worker configures its test.
const storageEnv = "STORAGE_CONNECTION_STRING"

// ciEnv is set to "true" by GitHub Actions and by every other CI this could
// run on. It is the difference between a developer without Docker, for whom
// skipping is right, and an automated run that was supposed to provide the
// emulator and did not.
const ciEnv = "CI"

// guardEnv stops the child process below from spawning one of its own, in
// case the -test.run anchor is ever loosened.
const guardEnv = "GO_BLOB_EMULATOR_GUARD"

// requestFixture is the shared contract example the Java side round-trips too.
// Seeding from it rather than from a literal here means a request this adapter
// downloads is the request that side agrees to write.
const requestFixture = "../../../contracts/packing/v1/request-envelope.example.json"

const azuriteTimeout = 30 * time.Second

// TestBlobArtifacts is one test with subtests rather than several top-level
// tests so the skip below is stated exactly once. A run without Azurite has to
// say so in a line nobody can mistake for a pass.
func TestBlobArtifacts(t *testing.T) {
	connectionString := os.Getenv(storageEnv)
	if connectionString == "" {
		skipOrFail(t, fmt.Sprintf("%s is unset, so nothing in TestBlobArtifacts ran — both derived blob keys, the six "+
			"metadata names, the content-type header and the absence rules are asserted nowhere else. "+
			"Start the emulator with `docker compose up -d azurite` and set %s to its connection string "+
			"(DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=...;BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;) "+
			"to exercise them.", storageEnv, storageEnv))
	}

	t.Run("downloads the version 1 request from the derived key", func(t *testing.T) {
		ctx, client, cfg := newAzuriteContainer(t, connectionString)
		artifacts := NewBlobArtifacts(client, cfg)
		jobID := newJobID(t)

		fixture, err := os.ReadFile(requestFixture)
		if err != nil {
			t.Fatalf("read request fixture: %v", err)
		}
		// The key is spelled out rather than built with requestKey, so a change
		// to requestKey fails here instead of moving the test with it. This is
		// the layout AzurePackingJobArtifactStore.requestKey writes.
		putBlob(t, ctx, client, cfg, "packing-jobs/"+jobID+"/request.json", fixture, nil)

		path := filepath.Join(t.TempDir(), "request.json")
		if err := artifacts.DownloadRequest(ctx, jobID, path); err != nil {
			t.Fatalf("DownloadRequest: %v", err)
		}

		downloaded, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read downloaded request: %v", err)
		}
		envelope, err := contracts.DecodeRequest(downloaded)
		if err != nil {
			t.Fatalf("decode downloaded request: %v", err)
		}
		if envelope.RequestVersion != contracts.MessageVersion {
			t.Errorf("requestVersion = %d, want %d", envelope.RequestVersion, contracts.MessageVersion)
		}
		if envelope.MaxRuntimeSeconds != 60 {
			t.Errorf("maxRuntimeSeconds = %d, want 60", envelope.MaxRuntimeSeconds)
		}
		if got, want := compactJSON(t, envelope.Spec), `{"testField":"value"}`; got != want {
			t.Errorf("spec = %s, want %s", got, want)
		}
	})

	t.Run("reports a missing request rather than an empty file", func(t *testing.T) {
		ctx, client, cfg := newAzuriteContainer(t, connectionString)
		artifacts := NewBlobArtifacts(client, cfg)

		path := filepath.Join(t.TempDir(), "request.json")
		err := artifacts.DownloadRequest(ctx, newJobID(t), path)
		if err == nil {
			t.Fatal("DownloadRequest succeeded for a job with no request blob")
		}
		if !bloberror.HasCode(err, bloberror.BlobNotFound) {
			t.Errorf("DownloadRequest error = %v, want a BlobNotFound", err)
		}
	})

	t.Run("reports no result before one exists", func(t *testing.T) {
		ctx, client, cfg := newAzuriteContainer(t, connectionString)
		artifacts := NewBlobArtifacts(client, cfg)

		stored, err := artifacts.FindResult(ctx, newJobID(t))
		if err != nil {
			t.Fatalf("FindResult: %v", err)
		}
		if stored != nil {
			t.Fatalf("FindResult = %+v, want nil for a job with no result", *stored)
		}
	})

	// The two subtests below pin the width of the absence rule from both sides.
	// Narrowing it to BlobNotFound alone, or widening it to every error, are
	// both one-token edits that the happy-path tests cannot see.
	t.Run("reports no result when the container does not exist", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), azuriteTimeout)
		defer cancel()

		// Deliberately never created: before the Java side writes the first
		// request, the container is not there, and that is still "no result for
		// this job" rather than a failure worth a redelivery.
		cfg := config.Config{
			StorageConnectionString: connectionString,
			StorageContainer:        "worker-blob-absent-" + randomHex(t, 8),
		}
		client, err := NewBlobClient(cfg)
		if err != nil {
			t.Fatalf("NewBlobClient: %v", err)
		}

		stored, err := NewBlobArtifacts(client, cfg).FindResult(ctx, newJobID(t))
		if err != nil {
			t.Fatalf("FindResult against a missing container: %v", err)
		}
		if stored != nil {
			t.Errorf("FindResult = %+v, want nil for a missing container", *stored)
		}
	})

	t.Run("reports a rejected credential as an error, not as absence", func(t *testing.T) {
		ctx, _, cfg := newAzuriteContainer(t, connectionString)

		// A 403 is a transport failure the processor abandons the delivery for.
		// Collapsing it into (nil, nil) would make an expired credential read as
		// "not packed yet", so a redelivery would re-run the packer instead of
		// resending the stored result, and the loss would be invisible.
		cfg.StorageConnectionString = withWrongAccountKey(t, connectionString)
		client, err := NewBlobClient(cfg)
		if err != nil {
			t.Fatalf("NewBlobClient: %v", err)
		}

		stored, err := NewBlobArtifacts(client, cfg).FindResult(ctx, newJobID(t))
		if err == nil {
			t.Fatalf("FindResult = %v, nil; want an error for a credential the service rejects", stored)
		}
		if stored != nil {
			t.Errorf("FindResult = %+v alongside an error, want nil", *stored)
		}
		if bloberror.HasCode(err, bloberror.BlobNotFound, bloberror.ContainerNotFound) {
			t.Errorf("FindResult error = %v, want something other than a 404", err)
		}
	})

	t.Run("finds an existing result from the derived key and its metadata", func(t *testing.T) {
		ctx, client, cfg := newAzuriteContainer(t, connectionString)
		artifacts := NewBlobArtifacts(client, cfg)
		jobID := newJobID(t)
		body := []byte(`{"placements":[]}`)

		// Metadata spelled as the Java side writes and reads it, and the key
		// spelled as AzurePackingJobArtifactStore.resultKey derives it.
		putBlob(t, ctx, client, cfg, "packing-jobs/"+jobID+"/result/output", body, map[string]*string{
			"fileName":             to.Ptr("output"),
			"contentType":          to.Ptr("application/octet-stream"),
			"checksumSha256":       to.Ptr(strings.Repeat("a", 64)),
			"engineVersion":        to.Ptr("packer 0.3.0"),
			"engineChecksumSha256": to.Ptr(strings.Repeat("b", 64)),
			"contractVersion":      to.Ptr("1"),
		})

		stored, err := artifacts.FindResult(ctx, jobID)
		if err != nil {
			t.Fatalf("FindResult: %v", err)
		}
		if stored == nil {
			t.Fatal("FindResult = nil, want the seeded result")
		}
		want := pipeline.StoredResult{
			Engine: pipeline.EngineProvenance{Version: "packer 0.3.0", Checksum: strings.Repeat("b", 64)},
			Result: pipeline.EngineResult{
				FileName:    "output",
				ContentType: "application/octet-stream",
				SizeBytes:   int64(len(body)),
				Checksum:    strings.Repeat("a", 64),
			},
		}
		if !reflect.DeepEqual(*stored, want) {
			t.Errorf("FindResult = %+v, want %+v", *stored, want)
		}
	})

	t.Run("creates a result carrying the six metadata values", func(t *testing.T) {
		ctx, client, cfg := newAzuriteContainer(t, connectionString)
		artifacts := NewBlobArtifacts(client, cfg)
		jobID := newJobID(t)
		body := []byte(`{"placements":[{"id":"a"}]}`)
		// NOT application/octet-stream. That is what Azure and Azurite store by
		// default when no x-ms-blob-content-type is sent, so asserting it would
		// pass with the BlobContentType header dropped entirely — the content
		// type would be right by coincidence rather than because this adapter
		// set it. engine/runner.go hardcodes octet-stream today, but the SAS
		// download URL the Java side issues sets no rsct override, so the blob's
		// own header is what a browser receives.
		const contentType = "application/vnd.packing+json"
		result := pipeline.StoredResult{
			Engine: pipeline.EngineProvenance{Version: "packer 0.3.0", Checksum: strings.Repeat("c", 64)},
			Result: pipeline.EngineResult{
				FileName:    "output",
				ContentType: contentType,
				SizeBytes:   int64(len(body)),
				Checksum:    strings.Repeat("d", 64),
			},
		}

		created, err := artifacts.CreateResult(ctx, jobID, writeTempFile(t, body), result)
		if err != nil {
			t.Fatalf("CreateResult: %v", err)
		}
		if created == nil {
			t.Fatal("CreateResult = nil, want the created metadata")
		}
		if !reflect.DeepEqual(*created, result) {
			t.Errorf("CreateResult = %+v, want %+v", *created, result)
		}

		properties, downloaded := readBlob(t, ctx, client, cfg, "packing-jobs/"+jobID+"/result/output")
		if !bytes.Equal(downloaded, body) {
			t.Errorf("stored body = %q, want %q", downloaded, body)
		}
		if properties.ContentLength == nil || *properties.ContentLength != int64(len(body)) {
			t.Errorf("stored content length = %s, want %d", showInt64(properties.ContentLength), len(body))
		}
		if properties.ContentType == nil || *properties.ContentType != contentType {
			t.Errorf("stored content type = %s, want %s", showString(properties.ContentType), contentType)
		}
		// The names are the cross-language contract; the values are what
		// AzurePackingJobArtifactStore.findResult hands the download endpoint.
		// Matched case-insensitively because Azure returns metadata as
		// x-ms-meta-* headers and net/http canonicalises a header name on the
		// way in — an exact-case assertion here would be asserting the SDK's
		// canonicaliser, not the contract. The Java side compares
		// case-insensitively for the same reason.
		wantMetadata := map[string]string{
			"fileName":             "output",
			"contentType":          contentType,
			"checksumSha256":       strings.Repeat("d", 64),
			"engineVersion":        "packer 0.3.0",
			"engineChecksumSha256": strings.Repeat("c", 64),
			"contractVersion":      strconv.Itoa(contracts.MessageVersion),
		}
		if len(properties.Metadata) != len(wantMetadata) {
			t.Errorf("stored %d metadata entries, want %d: %v", len(properties.Metadata), len(wantMetadata), metadataNames(properties.Metadata))
		}
		for name, want := range wantMetadata {
			if got := resultMetadataValue(properties.Metadata, name); got != want {
				t.Errorf("stored metadata %s = %q, want %q (present: %v)", name, got, want, metadataNames(properties.Metadata))
			}
		}
	})

	t.Run("concurrent creates agree on one winner", func(t *testing.T) {
		ctx, client, cfg := newAzuriteContainer(t, connectionString)
		artifacts := NewBlobArtifacts(client, cfg)
		jobID := newJobID(t)

		// Deliberately different in every field, and different in length, so
		// the bytes in storage can be matched back to the metadata both callers
		// report. A loser that answered with its own metadata would describe a
		// blob nobody can download.
		candidates := []struct {
			body   []byte
			stored pipeline.StoredResult
		}{
			{
				body: []byte(`{"writer":"a"}`),
				stored: pipeline.StoredResult{
					Engine: pipeline.EngineProvenance{Version: "packer 0.3.0", Checksum: strings.Repeat("1", 64)},
					Result: pipeline.EngineResult{FileName: "output", ContentType: "application/octet-stream", SizeBytes: 14, Checksum: strings.Repeat("2", 64)},
				},
			},
			{
				body: []byte(`{"writer":"b","padding":"longer"}`),
				stored: pipeline.StoredResult{
					Engine: pipeline.EngineProvenance{Version: "packer 0.4.0", Checksum: strings.Repeat("3", 64)},
					Result: pipeline.EngineResult{FileName: "output", ContentType: "application/octet-stream", SizeBytes: 33, Checksum: strings.Repeat("4", 64)},
				},
			},
		}
		for i, candidate := range candidates {
			if int64(len(candidate.body)) != candidate.stored.Result.SizeBytes {
				t.Fatalf("candidate %d declares %d bytes but its body is %d", i, candidate.stored.Result.SizeBytes, len(candidate.body))
			}
		}

		paths := []string{writeTempFile(t, candidates[0].body), writeTempFile(t, candidates[1].body)}
		reported := make([]*pipeline.StoredResult, len(candidates))
		errs := make([]error, len(candidates))
		start := make(chan struct{})
		var wg sync.WaitGroup
		for i := range candidates {
			wg.Add(1)
			go func() {
				defer wg.Done()
				<-start
				reported[i], errs[i] = artifacts.CreateResult(ctx, jobID, paths[i], candidates[i].stored)
			}()
		}
		close(start)
		wg.Wait()

		for i, err := range errs {
			if err != nil {
				t.Fatalf("CreateResult from caller %d: %v", i, err)
			}
			if reported[i] == nil {
				t.Fatalf("CreateResult from caller %d = nil, want metadata", i)
			}
		}
		if !reflect.DeepEqual(*reported[0], *reported[1]) {
			t.Fatalf("callers disagree on the result: %+v vs %+v", *reported[0], *reported[1])
		}

		winner := *reported[0]
		if winner != candidates[0].stored && winner != candidates[1].stored {
			t.Fatalf("agreed result %+v is neither caller's", winner)
		}

		if blobs := listBlobs(t, ctx, client, cfg, "packing-jobs/"+jobID+"/"); len(blobs) != 1 {
			t.Fatalf("stored %d blobs (%v), want exactly one", len(blobs), blobs)
		}

		properties, downloaded := readBlob(t, ctx, client, cfg, "packing-jobs/"+jobID+"/result/output")
		wantBody := candidates[0].body
		if winner == candidates[1].stored {
			wantBody = candidates[1].body
		}
		if !bytes.Equal(downloaded, wantBody) {
			t.Errorf("stored body = %q, want the winner's %q", downloaded, wantBody)
		}
		if properties.ContentLength == nil || *properties.ContentLength != winner.Result.SizeBytes {
			t.Errorf("stored content length = %s, want the reported %d", showInt64(properties.ContentLength), winner.Result.SizeBytes)
		}
		if got := resultMetadataValue(properties.Metadata, metaEngineChecksum); got != winner.Engine.Checksum {
			t.Errorf("stored engine checksum = %q, want the reported %q", got, winner.Engine.Checksum)
		}
		if got := resultMetadataValue(properties.Metadata, metaChecksum); got != winner.Result.Checksum {
			t.Errorf("stored result checksum = %q, want the reported %q", got, winner.Result.Checksum)
		}
	})
}

// TestBlobArtifactsRefusesToSkipUnderCI re-runs TestBlobArtifacts in a child
// process shaped like a CI job that forgot its emulator: CI set, the
// connection string cleared. The suite above is the only place both blob
// keys, the six metadata names and the content-type header are spelled out,
// and `go test` prints nothing for a skip without -v — so a workflow that
// lost all eight subtests reported `ok`, and a renamed metadata constant
// would have reached production green.
//
// A child process rather than a helper call because the rule being pinned is
// the exit status of the run, which is the only thing CI reads.
func TestBlobArtifactsRefusesToSkipUnderCI(t *testing.T) {
	if os.Getenv(guardEnv) == "1" {
		t.Skip("child process: TestBlobArtifacts is what this run is for")
	}

	child := exec.Command(os.Args[0], "-test.run=^TestBlobArtifacts$", "-test.v")
	child.Env = append(environmentWithout(storageEnv), ciEnv+"=true", guardEnv+"=1")
	output, err := child.CombinedOutput()

	if err == nil {
		t.Fatalf("TestBlobArtifacts reported success under %s with no %s, so a CI run that never reached a blob is green:\n%s",
			ciEnv, storageEnv, output)
	}
	if !strings.Contains(string(output), storageEnv) {
		t.Errorf("the failure never names %s, so nobody reading it learns what the job has to set:\n%s", storageEnv, output)
	}
	if strings.Contains(string(output), "--- SKIP") {
		t.Errorf("TestBlobArtifacts skipped instead of failing under %s:\n%s", ciEnv, output)
	}
}

func environmentWithout(name string) []string {
	prefix := name + "="
	kept := make([]string, 0, len(os.Environ()))
	for _, entry := range os.Environ() {
		if !strings.HasPrefix(entry, prefix) {
			kept = append(kept, entry)
		}
	}
	return kept
}

// newAzuriteContainer gives a subtest a container of its own, so nothing here
// depends on the order subtests run in or on state a previous run left behind.
func newAzuriteContainer(t *testing.T, connectionString string) (context.Context, *azblob.Client, config.Config) {
	t.Helper()

	cfg := config.Config{
		StorageConnectionString: connectionString,
		StorageContainer:        "worker-blob-" + randomHex(t, 8),
	}
	client, err := NewBlobClient(cfg)
	if err != nil {
		t.Fatalf("NewBlobClient: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), azuriteTimeout)
	t.Cleanup(cancel)

	if _, err := client.CreateContainer(ctx, cfg.StorageContainer, nil); err != nil {
		t.Fatalf("create container %s (is Azurite running?): %v", cfg.StorageContainer, err)
	}
	t.Cleanup(func() {
		// Its own context: the test's may already be spent, and only the
		// container this test created is removed — never a volume, never the
		// emulator's other data.
		cleanupCtx, done := context.WithTimeout(context.Background(), azuriteTimeout)
		defer done()
		if _, err := client.DeleteContainer(cleanupCtx, cfg.StorageContainer, nil); err != nil {
			t.Logf("delete container %s: %v", cfg.StorageContainer, err)
		}
	})

	return ctx, client, cfg
}

func containerClient(client *azblob.Client, cfg config.Config) *container.Client {
	return client.ServiceClient().NewContainerClient(cfg.StorageContainer)
}

func putBlob(t *testing.T, ctx context.Context, client *azblob.Client, cfg config.Config, key string, body []byte, metadata map[string]*string) {
	t.Helper()
	if _, err := client.UploadBuffer(ctx, cfg.StorageContainer, key, body, &azblob.UploadBufferOptions{Metadata: metadata}); err != nil {
		t.Fatalf("seed blob %s: %v", key, err)
	}
}

func readBlob(t *testing.T, ctx context.Context, client *azblob.Client, cfg config.Config, key string) (blob.GetPropertiesResponse, []byte) {
	t.Helper()
	properties, err := containerClient(client, cfg).NewBlobClient(key).GetProperties(ctx, nil)
	if err != nil {
		t.Fatalf("read properties of %s: %v", key, err)
	}
	stream, err := client.DownloadStream(ctx, cfg.StorageContainer, key, nil)
	if err != nil {
		t.Fatalf("download %s: %v", key, err)
	}
	defer func() { _ = stream.Body.Close() }()
	body := new(bytes.Buffer)
	if _, err := body.ReadFrom(stream.Body); err != nil {
		t.Fatalf("read %s: %v", key, err)
	}
	return properties, body.Bytes()
}

func listBlobs(t *testing.T, ctx context.Context, client *azblob.Client, cfg config.Config, prefix string) []string {
	t.Helper()
	var names []string
	pager := containerClient(client, cfg).NewListBlobsFlatPager(&container.ListBlobsFlatOptions{Prefix: to.Ptr(prefix)})
	for pager.More() {
		page, err := pager.NextPage(ctx)
		if err != nil {
			t.Fatalf("list blobs under %s: %v", prefix, err)
		}
		for _, item := range page.Segment.BlobItems {
			if item.Name != nil {
				names = append(names, *item.Name)
			}
		}
	}
	return names
}

// withWrongAccountKey swaps the account key for another well-formed one, so the
// request is signed and sent and the service is what rejects it. Anything that
// failed client-side would prove nothing about how a service error is
// classified.
func withWrongAccountKey(t *testing.T, connectionString string) string {
	t.Helper()

	const keyPrefix = "AccountKey="
	parts := strings.Split(connectionString, ";")
	replaced := false
	for i, part := range parts {
		if strings.HasPrefix(part, keyPrefix) {
			parts[i] = keyPrefix + base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{1}, 64))
			replaced = true
		}
	}
	if !replaced {
		skipOrFail(t, fmt.Sprintf("%s carries no AccountKey, so this subtest cannot forge a credential the service will reject", storageEnv))
	}
	return strings.Join(parts, ";")
}

// skipOrFail reports a missing prerequisite. On a developer's machine that is
// a missing emulator and skipping is right. Under CI the emulator is the
// workflow's job to start, so the same condition is a broken workflow — and
// `go test` prints nothing for a skip without -v, which is how a run that
// executed none of these assertions still printed `ok`.
func skipOrFail(t *testing.T, reason string) {
	t.Helper()
	if os.Getenv(ciEnv) != "" {
		t.Fatal("FAILING rather than skipping an Azurite-backed test, because " + ciEnv + " is set: " + reason)
	}
	t.Skip("SKIPPING an Azurite-backed test: " + reason)
}

func writeTempFile(t *testing.T, body []byte) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "output")
	if err := os.WriteFile(path, body, 0o600); err != nil {
		t.Fatalf("write temp file: %v", err)
	}
	return path
}

// showString and showInt64 keep a failing assertion readable: the SDK models
// these as pointers, and %v on one prints an address rather than the value the
// assertion is about.
func showString(value *string) string {
	if value == nil {
		return "<nil>"
	}
	return *value
}

func showInt64(value *int64) string {
	if value == nil {
		return "<nil>"
	}
	return strconv.FormatInt(*value, 10)
}

func metadataNames(metadata map[string]*string) []string {
	names := make([]string, 0, len(metadata))
	for name := range metadata {
		names = append(names, name)
	}
	return names
}

func compactJSON(t *testing.T, raw []byte) string {
	t.Helper()
	buf := new(bytes.Buffer)
	if err := json.Compact(buf, raw); err != nil {
		t.Fatalf("compact json: %v", err)
	}
	return buf.String()
}

func newJobID(t *testing.T) string {
	t.Helper()
	raw := randomHex(t, 16)
	return raw[0:8] + "-" + raw[8:12] + "-" + raw[12:16] + "-" + raw[16:20] + "-" + raw[20:32]
}

func randomHex(t *testing.T, n int) string {
	t.Helper()
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		t.Fatalf("read random bytes: %v", err)
	}
	return hex.EncodeToString(buf)
}
