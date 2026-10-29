package com.packing.backend.infra.packing.temporal;

import com.packing.backend.domain.packing.event.PackingJobCancelled;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@RequiredArgsConstructor
@Slf4j
class PackingJobCancelledListener {

    private final WorkflowClient client;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(PackingJobCancelled event) {
        String workflowId = PackingEngineContract.WORKFLOW_ID_PREFIX + event.jobId();
        try {
            client.newUntypedWorkflowStub(workflowId)
                  .cancel();
        } catch (WorkflowNotFoundException closedOrNeverStarted) {
            log.debug("No open packing workflow {} to cancel", workflowId);
        } catch (RuntimeException failure) {
            log.warn("Packing workflow {} was not cancelled; its result will be discarded", workflowId, failure);
        }
    }
}
