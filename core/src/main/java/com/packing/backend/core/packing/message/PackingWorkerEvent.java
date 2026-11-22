package com.packing.backend.core.packing.message;

import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.ResultBin;

import java.util.List;

public sealed interface PackingWorkerEvent {

    int messageVersion();

    PackingJobId jobId();

    record Started(int messageVersion, PackingJobId jobId,
            String engineVersion, String engineChecksum) implements PackingWorkerEvent {
    }

    record Succeeded(int messageVersion, PackingJobId jobId,
            String engineVersion, String engineChecksum,
            int binCount) implements PackingWorkerEvent {
    }

    record ResultWritten(int messageVersion, PackingJobId jobId,
            String resultFileName, String resultContentType,
            long resultSizeBytes, String resultChecksum,
            List<ResultBin> resultBins) implements PackingWorkerEvent {
    }

    record ResultFailed(int messageVersion, PackingJobId jobId) implements PackingWorkerEvent {
    }

    record Failed(int messageVersion, PackingJobId jobId,
            String engineVersion, String engineChecksum,
            String reason) implements PackingWorkerEvent {
    }
}
