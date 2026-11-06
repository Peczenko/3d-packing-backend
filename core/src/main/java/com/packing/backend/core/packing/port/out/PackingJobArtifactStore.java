package com.packing.backend.core.packing.port.out;

import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PreviewQuality;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface PackingJobArtifactStore {

    void writeRequestIfAbsent(PackingJobId jobId, PackingRequestEnvelope envelope);

    Optional<ResultArtifact> findResult(PackingJobId jobId);

    TemporaryUrl createResultDownloadUrl(PackingJobId jobId, Duration validity);

    InputStream openResult(PackingJobId jobId);

    void storePreview(PackingJobId jobId, PreviewQuality quality, Path glb);

    TemporaryUrl createPreviewUrl(PackingJobId jobId, PreviewQuality quality, Duration validity, String attachmentFileName);

    record ResultArtifact(String fileName, String contentType, long sizeBytes,
            String checksum, String engineVersion, String engineChecksum) {
    }

    record TemporaryUrl(URI url, Instant expiresAt) {
    }
}
