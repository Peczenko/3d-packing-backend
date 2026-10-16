package com.packing.backend.infra.packing.temporal;

import com.packing.backend.core.packing.PackingJobRecoveryService;
import com.packing.backend.core.packing.PackingWorkerEventService;
import com.packing.backend.core.packing.message.PackingWorkerEvent;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineFailureDetails;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineResult;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineStarted;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

import static com.packing.backend.infra.packing.temporal.PackingEngineContract.VERSION;

@ActivityImpl(taskQueues = PackingEngineContract.STATE_TASK_QUEUE)
@RequiredArgsConstructor
public class PackingStateActivitiesImpl implements PackingStateActivities {

    private final PackingWorkerEventService workerEvents;
    private final PackingJobRecoveryService recovery;

    @Override
    public void recordStarted(String jobId, EngineStarted started) {
        workerEvents.apply(new PackingWorkerEvent.Started(VERSION,
                                                          id(jobId),
                                                          started.engineVersion(),
                                                          started.engineChecksum()));
    }

    @Override
    public void recordSucceeded(String jobId, EngineResult result) {
        workerEvents.apply(new PackingWorkerEvent.Succeeded(VERSION,
                                                            id(jobId),
                                                            result.engineVersion(),
                                                            result.engineChecksum(),
                                                            result.resultFileName(),
                                                            result.resultContentType(),
                                                            result.resultSizeBytes(),
                                                            result.resultChecksum()));
    }

    @Override
    public void recordFailed(String jobId, EngineFailureDetails failure) {
        workerEvents.apply(new PackingWorkerEvent.Failed(VERSION,
                                                         id(jobId),
                                                         failure.engineVersion(),
                                                         failure.engineChecksum(),
                                                         failure.reason()));
    }

    @Override
    public void recordStalled(String jobId, String reason) {
        recovery.failStalled(id(jobId), reason);
    }

    private static PackingJobId id(String jobId) {
        return new PackingJobId(UUID.fromString(jobId));
    }
}
