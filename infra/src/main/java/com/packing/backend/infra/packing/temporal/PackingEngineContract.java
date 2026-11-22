package com.packing.backend.infra.packing.temporal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

public final class PackingEngineContract {

    public static final int    VERSION            = 5;
    public static final String STATE_TASK_QUEUE   = "packing-state";
    public static final String ENGINE_TASK_QUEUE  = "packing-engine";
    public static final String RESULT_TASK_QUEUE  = "packing-result";
    public static final String WORKFLOW_ID_PREFIX = "packing-job-";
    public static final String ENGINE_FAILURE     = "EngineFailure";
    public static final String RESULT_FAILURE     = "ResultFailure";

    private PackingEngineContract() {
    }

    public record RunEngineInput(int contractVersion, String jobId) {
    }

    public record EngineStarted(int contractVersion, String engineVersion, String engineChecksum) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EngineResult(int contractVersion, String engineVersion, String engineChecksum, int binCount) {
    }

    public record WriteResultInput(int contractVersion, String jobId) {
    }

    public record ResultFiles(int contractVersion,
            String resultFileName,
            String resultContentType,
            long resultSizeBytes,
            String resultChecksum,
            List<ResultBin> resultBins) {
    }

    public record ResultBin(long sizeBytes, String checksum) {
    }

    public record EngineFailureDetails(int contractVersion, String engineVersion, String engineChecksum, String reason) {
    }
}
