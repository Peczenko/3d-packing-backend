package com.packing.backend.infra.packing.temporal;

import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineFailureDetails;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineResult;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineStarted;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface PackingStateActivities {

    void recordStarted(String jobId, EngineStarted started);

    void recordSucceeded(String jobId, EngineResult result);

    void recordFailed(String jobId, EngineFailureDetails failure);

    void recordStalled(String jobId, String reason);
}
