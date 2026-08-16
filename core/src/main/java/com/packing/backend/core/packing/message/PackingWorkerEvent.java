package com.packing.backend.core.packing.message;

import com.packing.backend.domain.packing.PackingJobId;

public sealed interface PackingWorkerEvent {

    int messageVersion();

    PackingJobId jobId();

    String engineVersion();

    String engineChecksum();

    record Started(int messageVersion, PackingJobId jobId,
            String engineVersion, String engineChecksum) implements PackingWorkerEvent {
    }

    record Succeeded(int messageVersion, PackingJobId jobId,
            String engineVersion, String engineChecksum,
            String resultFileName, String resultContentType,
            long resultSizeBytes, String resultChecksum) implements PackingWorkerEvent {
    }

    record Failed(int messageVersion, PackingJobId jobId,
            String engineVersion, String engineChecksum,
            String reason) implements PackingWorkerEvent {
    }
}
