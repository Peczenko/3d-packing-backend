package com.packing.backend.infra.packing.temporal;

import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineStarted;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface PackingJobWorkflow {

    @WorkflowMethod(name = "PackingJob")
    void run(PackingJobInput input);

    @SignalMethod(name = "engineStarted")
    void engineStarted(EngineStarted signal);

    record PackingJobInput(String jobId, long maxRuntimeSeconds) {
    }
}
