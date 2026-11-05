package com.packing.backend.infra.packing.temporal;

import com.packing.backend.core.packing.message.PackingDispatchMessage;
import com.packing.backend.core.packing.port.out.PackingDispatchSender;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
class TemporalPackingDispatchSender implements PackingDispatchSender {

    static final WorkflowIdReusePolicy REUSE_POLICY = WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;

    private final WorkflowClient       client;
    private final PackingJobRepository jobs;

    @Override
    public void send(PackingDispatchMessage message) {
        PackingJob job = jobs.findById(message.jobId())
                             .orElseThrow(() -> PackingJobNotFoundException.byId(message.jobId()));
        String jobId = message.jobId()
                              .toString();
        PackingJobWorkflow workflow = client.newWorkflowStub(PackingJobWorkflow.class,
                                                             WorkflowOptions.newBuilder()
                                                                            .setWorkflowId(PackingEngineContract.WORKFLOW_ID_PREFIX + jobId)
                                                                            .setTaskQueue(PackingEngineContract.STATE_TASK_QUEUE)
                                                                            .setWorkflowIdReusePolicy(REUSE_POLICY)
                                                                            .build());
        try {
            WorkflowClient.start(workflow::run, new PackingJobWorkflow.PackingJobInput(jobId, job.maxRuntimeSeconds()));
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            log.debug("Packing workflow for job {} was already started", jobId);
        }
    }
}
