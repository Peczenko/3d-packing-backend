package com.packing.backend.core.packing;

import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.packing.PackingPreview;
import com.packing.backend.domain.packing.ResultBin;
import com.packing.backend.domain.packing.ResultStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PackingJobView(
        UUID id,
        UUID projectId,
        PackingJobStatus status,
        String engineVersion,
        String engineChecksum,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String failureReason,
        ResultStatus resultStatus,
        String resultFileName,
        String resultContentType,
        Long resultSizeBytes,
        String resultChecksum,
        int binCount,
        List<ResultBin> resultBins,
        PackingJobInputView input,
        PackingPreview preview) {
}
