package com.packing.backend.infra.packing.temporal;

public final class PackingEngineContract {

    public static final int    VERSION            = 3;
    public static final String STATE_TASK_QUEUE   = "packing-state";
    public static final String ENGINE_TASK_QUEUE  = "packing-engine";
    public static final String WORKFLOW_ID_PREFIX = "packing-job-";
    public static final String ENGINE_FAILURE     = "EngineFailure";

    private PackingEngineContract() {
    }

    public record RunEngineInput(int contractVersion, String jobId) {
    }

    public record EngineStarted(int contractVersion, String engineVersion, String engineChecksum) {
    }

    public record EngineResult(int contractVersion,
            String engineVersion,
            String engineChecksum,
            String resultFileName,
            String resultContentType,
            long resultSizeBytes,
            String resultChecksum) {
    }

    public record EngineFailureDetails(int contractVersion, String engineVersion, String engineChecksum, String reason) {
    }
}
