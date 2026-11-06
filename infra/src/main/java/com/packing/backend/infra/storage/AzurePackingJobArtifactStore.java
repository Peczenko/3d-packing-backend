package com.packing.backend.infra.storage;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.options.BlobUploadFromFileOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PreviewQuality;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.infra.packing.PackingContractCodec;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
public class AzurePackingJobArtifactStore implements PackingJobArtifactStore {

    private static final String   SERVICE                      = "azure-blob-storage";
    private static final int      CLOCK_SKEW_ALLOWANCE_MINUTES = 5;
    private static final String   PREVIEW_CONTENT_TYPE         = "model/gltf-binary";
    private static final String   PREVIEW_CACHE_CONTROL        = "private, max-age=86400, immutable";
    private static final Duration CLOCK_SKEW_ALLOWANCE         = Duration.ofMinutes(CLOCK_SKEW_ALLOWANCE_MINUTES);

    private final BlobContainerClient  container;
    private final BlobSasIssuer        sasIssuer;
    private final PackingContractCodec codec;
    private final AtomicBoolean        containerEnsured = new AtomicBoolean();

    @Override
    public void writeRequestIfAbsent(PackingJobId jobId, PackingRequestEnvelope envelope) {
        byte[] request = codec.encodeRequest(envelope)
                              .getBytes(StandardCharsets.UTF_8);
        BlobClient blob = ensureContainer(jobId).getBlobClient(requestKey(jobId));
        try {
            blob.uploadWithResponse(new BlobParallelUploadOptions(new ByteArrayInputStream(request))
                                                                                                    .setHeaders(new BlobHttpHeaders().setContentType("application/json"))
                                                                                                    .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")),
                                    null,
                                    Context.NONE);
        } catch (BlobStorageException e) {
            if (isAlreadyExists(e)) {
                acceptOnlyIdenticalRequest(blob, request, jobId);
                return;
            }
            throw external("write request", jobId, e);
        }
    }

    @Override
    public Optional<ResultArtifact> findResult(PackingJobId jobId) {
        try {
            BlobProperties properties = container.getBlobClient(resultKey(jobId))
                                                 .getProperties();
            Map<String, String> metadata = properties.getMetadata();
            if (properties.getBlobSize() <= 0) {
                throw invalidResult(jobId, "a positive size");
            }
            return Optional.of(new ResultArtifact(
                                                  requiredMetadata(metadata, "fileName", jobId),
                                                  requiredMetadata(metadata, "contentType", jobId),
                                                  properties.getBlobSize(),
                                                  requiredMetadata(metadata, "checksumSha256", jobId),
                                                  requiredMetadata(metadata, "engineVersion", jobId),
                                                  requiredMetadata(metadata, "engineChecksumSha256", jobId)));
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw external("read result metadata", jobId, e);
        }
    }

    @Override
    public TemporaryUrl createResultDownloadUrl(PackingJobId jobId, Duration validity) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiry = now.plus(validity);
        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(
                                                                                 expiry,
                                                                                 new BlobSasPermission().setReadPermission(true))
                                                                                                                                 .setStartTime(now.minusMinutes(CLOCK_SKEW_ALLOWANCE_MINUTES));
        BlobClient blob = container.getBlobClient(resultKey(jobId));
        try {
            return new TemporaryUrl(
                                    URI.create(blob.getBlobUrl() + "?" + sasIssuer.sasToken(blob, values)),
                                    expiry.toInstant());
        } catch (BlobStorageException e) {
            throw external("issue result download URL", jobId, e);
        }
    }

    @Override
    public InputStream openResult(PackingJobId jobId) {
        try {
            return container.getBlobClient(resultKey(jobId))
                            .openInputStream();
        } catch (BlobStorageException e) {
            throw external("open result", jobId, e);
        }
    }

    @Override
    public void storePreview(PackingJobId jobId, PreviewQuality quality, Path glb) {
        try {
            container.getBlobClient(previewKey(jobId, quality))
                     .uploadFromFileWithResponse(new BlobUploadFromFileOptions(glb.toString())
                                                                                              .setHeaders(new BlobHttpHeaders().setContentType(PREVIEW_CONTENT_TYPE)
                                                                                                                               .setCacheControl(PREVIEW_CACHE_CONTROL)),
                                                 null,
                                                 Context.NONE);
        } catch (BlobStorageException e) {
            throw external("store " + quality + " preview", jobId, e);
        }
    }

    @Override
    public TemporaryUrl createPreviewUrl(PackingJobId jobId, PreviewQuality quality, Duration validity, String attachmentFileName) {
        SasWindow window = SasWindow.containing(Instant.now(), validity);
        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(
                                                                                 window.expiry()
                                                                                       .atOffset(ZoneOffset.UTC),
                                                                                 new BlobSasPermission().setReadPermission(true))
                                                                                                                                 .setStartTime(window.start()
                                                                                                                                                     .atOffset(ZoneOffset.UTC));
        if (attachmentFileName != null) {
            values.setContentDisposition(AttachmentDisposition.of(attachmentFileName));
        }
        BlobClient blob = container.getBlobClient(previewKey(jobId, quality));
        try {
            return new TemporaryUrl(URI.create(blob.getBlobUrl() + "?" + sasIssuer.sasToken(blob, values)), window.expiry());
        } catch (BlobStorageException e) {
            throw external("issue " + quality + " preview URL", jobId, e);
        }
    }

    record SasWindow(Instant start, Instant expiry) {

        static SasWindow containing(Instant now, Duration validity) {
            long seconds = Math.max(1, validity.toSeconds());
            long epoch = now.getEpochSecond();
            Instant windowStart = Instant.ofEpochSecond(epoch - Math.floorMod(epoch, seconds));
            return new SasWindow(windowStart.minus(CLOCK_SKEW_ALLOWANCE), windowStart.plusSeconds(2 * seconds));
        }
    }

    private void acceptOnlyIdenticalRequest(BlobClient blob, byte[] requested, PackingJobId jobId) {
        try {
            if (!java.util.Arrays.equals(requested,
                                         blob.downloadContent()
                                             .toBytes())) {
                throw new ResourceConflictException("Packing job request already exists with different content");
            }
        } catch (BlobStorageException e) {
            throw external("read existing request", jobId, e);
        }
    }

    private static boolean isAlreadyExists(BlobStorageException exception) {
        return exception.getStatusCode() == 409 || exception.getStatusCode() == 412;
    }

    private String requiredMetadata(Map<String, String> metadata, String name, PackingJobId jobId) {
        if (metadata == null) {
            throw invalidResult(jobId, "required metadata " + name);
        }
        String value = metadata.entrySet()
                               .stream()
                               .filter(entry -> entry.getKey()
                                                     .equalsIgnoreCase(name))
                               .map(Map.Entry::getValue)
                               .findFirst()
                               .orElse(null);
        if (value == null || value.isBlank()) {
            throw invalidResult(jobId, "required metadata " + name);
        }
        return value;
    }

    private ExternalServiceException invalidResult(PackingJobId jobId, String problem) {
        return new ExternalServiceException(SERVICE,
                                            "Result artifact for packing job " + jobId + " is missing " + problem);
    }

    private BlobContainerClient ensureContainer(PackingJobId jobId) {
        if (containerEnsured.get()) {
            return container;
        }
        try {
            container.createIfNotExists();
            containerEnsured.set(true);
            return container;
        } catch (BlobStorageException e) {
            throw external("create storage container", jobId, e);
        }
    }

    private ExternalServiceException external(String operation, PackingJobId jobId,
                                              BlobStorageException exception) {
        return new ExternalServiceException(SERVICE,
                                            "Could not " + operation + " for packing job " + jobId,
                                            exception);
    }

    private static String requestKey(PackingJobId id) {
        return "packing-jobs/" + id + "/request.json";
    }

    private static String resultKey(PackingJobId id) {
        return "packing-jobs/" + id + "/result/output";
    }

    private static String previewKey(PackingJobId id, PreviewQuality quality) {
        return "packing-jobs/" + id + "/preview/" + quality.name()
                                                           .toLowerCase(Locale.ROOT)
                + ".glb";
    }
}
