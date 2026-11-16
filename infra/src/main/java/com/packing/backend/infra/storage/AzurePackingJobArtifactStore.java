package com.packing.backend.infra.storage;

import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.port.out.PackingArtifact;
import com.packing.backend.core.packing.port.out.PackingArtifact.BinPreview;
import com.packing.backend.core.packing.port.out.PackingArtifact.BinResult;
import com.packing.backend.core.packing.port.out.PackingArtifact.Preview;
import com.packing.backend.core.packing.port.out.PackingArtifact.PreviewArtifact;
import com.packing.backend.core.packing.port.out.PackingArtifact.Result;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.core.shared.TemporaryUrl;
import com.packing.backend.domain.packing.PackingItem;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PreviewQuality;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.infra.packing.PackingContractCodec;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class AzurePackingJobArtifactStore implements PackingJobArtifactStore {

    private static final String PREVIEW_CONTENT_TYPE  = "model/gltf-binary";
    private static final String PREVIEW_CACHE_CONTROL = "private, max-age=86400, immutable";

    private final BlobContainer        container;
    private final PackingContractCodec codec;

    @Override
    public void writeRequestIfAbsent(PackingJobId jobId, PackingRequestEnvelope envelope) {
        byte[] request = codec.encodeRequest(envelope)
                              .getBytes(StandardCharsets.UTF_8);
        String key = requestKey(jobId);
        if (!container.uploadIfAbsent(key, request, new BlobHttpHeaders().setContentType("application/json"))
                && !Arrays.equals(request, container.download(key))) {
            throw new ResourceConflictException("Packing job request already exists with different content");
        }
    }

    @Override
    public Optional<ResultArtifact> findResult(PackingJobId jobId) {
        return container.findProperties(keyOf(new Result(jobId)))
                        .map(properties -> resultArtifact(jobId, properties));
    }

    @Override
    public InputStream open(PackingArtifact artifact) {
        return container.open(keyOf(artifact));
    }

    @Override
    public Optional<byte[]> findPlacements(PackingJobId jobId, String resultChecksum) {
        String key = "packing-jobs/" + jobId + "/result/placements/" + resultChecksum + ".json";
        return container.findProperties(key)
                        .map(properties -> container.download(key));
    }

    @Override
    public InputStream openInput(PackingItem item) {
        return container.open(item.storageKey()
                                  .value());
    }

    @Override
    public void storePreview(PreviewArtifact preview, Path glb) {
        container.upload(keyOf(preview),
                         glb,
                         new BlobHttpHeaders().setContentType(PREVIEW_CONTENT_TYPE)
                                              .setCacheControl(PREVIEW_CACHE_CONTROL));
    }

    @Override
    public TemporaryUrl readUrl(PackingArtifact artifact, Duration validity, boolean asAttachment) {
        ReadUrl request = ReadUrl.validFor(validity)
                                 .asAttachment(asAttachment ? artifact.downloadName() : null);
        return container.readUrl(keyOf(artifact), artifact instanceof PreviewArtifact ? request.cacheable() : request);
    }

    private static String keyOf(PackingArtifact artifact) {
        String job = "packing-jobs/" + artifact.jobId();
        return switch (artifact) {
            case Result ignored -> job + "/result/output";
            case BinResult bin -> bin.binCount() == 1
                                                      ? job + "/result/output"
                                                      : job + "/result/bins/" + bin.resultChecksum() + "/" + bin.number();
            case Preview preview -> job + "/preview/" + previewFile(preview.quality());
            case BinPreview bin -> bin.binCount() == 1
                                                       ? job + "/preview/" + previewFile(bin.quality())
                                                       : job + "/preview/bins/" + bin.number() + "/" + previewFile(bin.quality());
        };
    }

    private static String requestKey(PackingJobId jobId) {
        return "packing-jobs/" + jobId + "/request.json";
    }

    private static String previewFile(PreviewQuality quality) {
        return quality.name()
                      .toLowerCase(Locale.ROOT)
                + ".glb";
    }

    private static ResultArtifact resultArtifact(PackingJobId jobId, BlobProperties properties) {
        if (properties.getBlobSize() <= 0) {
            throw invalidResult(jobId, "a positive size");
        }
        Map<String, String> metadata = properties.getMetadata();
        return new ResultArtifact(requiredMetadata(metadata, "fileName", jobId),
                                  requiredMetadata(metadata, "contentType", jobId),
                                  decodedSize(metadata, properties.getBlobSize()),
                                  requiredMetadata(metadata, "checksumSha256", jobId),
                                  requiredMetadata(metadata, "engineVersion", jobId),
                                  requiredMetadata(metadata, "engineChecksumSha256", jobId));
    }

    private static long decodedSize(Map<String, String> metadata, long storedSize) {
        String recorded = metadataValue(metadata, "sizeBytes");
        if (recorded != null) {
            try {
                long size = Long.parseLong(recorded.strip());
                if (size > 0) {
                    return size;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return storedSize;
    }

    private static String requiredMetadata(Map<String, String> metadata, String name, PackingJobId jobId) {
        String value = metadataValue(metadata, name);
        if (value == null || value.isBlank()) {
            throw invalidResult(jobId, "required metadata " + name);
        }
        return value;
    }

    private static String metadataValue(Map<String, String> metadata, String name) {
        return metadata == null ? null
                                : metadata.entrySet()
                                          .stream()
                                          .filter(entry -> entry.getKey()
                                                                .equalsIgnoreCase(name))
                                          .map(Map.Entry::getValue)
                                          .findFirst()
                                          .orElse(null);
    }

    private static ExternalServiceException invalidResult(PackingJobId jobId, String problem) {
        return new ExternalServiceException(BlobContainer.SERVICE,
                                            "Result artifact for packing job " + jobId + " is missing " + problem);
    }
}
