package com.packing.backend.infra.storage;

import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PreviewQuality;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

class UnavailablePackingJobArtifactStore implements PackingJobArtifactStore {

    private static final String SERVICE = "azure-blob-storage";
    private static final String MESSAGE = "Object storage is not configured. Set app.storage.enabled=true and provide "
            + "spring.cloud.azure.storage.blob.connection-string (or .endpoint).";

    @Override
    public void writeRequestIfAbsent(PackingJobId jobId, PackingRequestEnvelope envelope) {
        throw unavailable();
    }

    @Override
    public Optional<ResultArtifact> findResult(PackingJobId jobId) {
        throw unavailable();
    }

    @Override
    public TemporaryUrl createResultDownloadUrl(PackingJobId jobId, Duration validity) {
        throw unavailable();
    }

    @Override
    public TemporaryUrl createResultBinUrl(PackingJobId jobId, String resultChecksum, int number, int binCount, Duration validity,
                                           String attachmentFileName) {
        throw unavailable();
    }

    @Override
    public InputStream openResult(PackingJobId jobId) {
        throw unavailable();
    }

    @Override
    public void storePreview(PackingJobId jobId, PreviewQuality quality, Path glb) {
        throw unavailable();
    }

    @Override
    public TemporaryUrl createPreviewUrl(PackingJobId jobId, PreviewQuality quality, Duration validity, String attachmentFileName) {
        throw unavailable();
    }

    private ExternalServiceException unavailable() {
        return new ExternalServiceException(SERVICE, MESSAGE);
    }
}
