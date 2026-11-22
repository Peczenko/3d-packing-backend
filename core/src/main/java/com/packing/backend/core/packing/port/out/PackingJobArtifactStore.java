package com.packing.backend.core.packing.port.out;

import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.port.out.PackingArtifact.PreviewArtifact;
import com.packing.backend.core.shared.TemporaryUrl;
import com.packing.backend.domain.packing.PackingItem;
import com.packing.backend.domain.packing.PackingJobId;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public interface PackingJobArtifactStore {

    void writeRequestIfAbsent(PackingJobId jobId, PackingRequestEnvelope envelope);

    Optional<ResultArtifact> findResult(PackingJobId jobId);

    InputStream open(PackingArtifact artifact);

    Optional<byte[]> findPlacements(PackingJobId jobId);

    InputStream openInput(PackingItem item);

    void storePreview(PreviewArtifact preview, Path glb);

    TemporaryUrl readUrl(PackingArtifact artifact, Duration validity, boolean asAttachment);

    record ResultArtifact(String fileName, String contentType, long sizeBytes,
            String checksum, String engineVersion, String engineChecksum) {
    }
}
