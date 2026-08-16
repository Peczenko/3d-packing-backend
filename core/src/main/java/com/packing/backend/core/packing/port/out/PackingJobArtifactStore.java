package com.packing.backend.core.packing.port.out;

import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.domain.packing.PackingJobId;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface PackingJobArtifactStore {

    void writeRequestIfAbsent(PackingJobId jobId, PackingRequestEnvelope envelope);

    Optional<ResultArtifact> findResult(PackingJobId jobId);

    TemporaryUrl createResultDownloadUrl(PackingJobId jobId, Duration validity);

    record ResultArtifact(String fileName, String contentType, long sizeBytes,
            String checksum, String engineVersion, String engineChecksum) {
    }

    record TemporaryUrl(URI url, Instant expiresAt) {
    }
}
