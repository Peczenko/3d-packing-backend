package com.packing.backend.infra.packing.temporal;

import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.event.PackingJobCancelled;
import com.packing.backend.domain.project.ProjectId;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackingJobCancelledListenerTest {

    private final WorkflowClient              client   = mock(WorkflowClient.class);
    private final WorkflowStub                workflow = mock(WorkflowStub.class);
    private final PackingJobCancelledListener listener = new PackingJobCancelledListener(client);
    private final PackingJobCancelled         event    = new PackingJobCancelled(PackingJobId.generate(),
                                                                                 ProjectId.generate(),
                                                                                 Instant.parse("2026-10-20T11:00:00Z"));

    @Test
    void cancelsTheJobsWorkflowAfterCommit() throws NoSuchMethodException {
        when(client.newUntypedWorkflowStub(workflowId())).thenReturn(workflow);

        listener.on(event);

        verify(workflow).cancel();
        assertThat(PackingJobCancelledListener.class.getDeclaredMethod("on", PackingJobCancelled.class)
                                                    .getAnnotation(TransactionalEventListener.class)
                                                    .phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void aWorkflowThatIsClosedOrNeverStartedIsNotAFailure() {
        when(client.newUntypedWorkflowStub(workflowId())).thenReturn(workflow);
        doThrow(new WorkflowNotFoundException(WorkflowExecution.newBuilder()
                                                               .setWorkflowId(workflowId())
                                                               .build(),
                                              "PackingJob",
                                              null)).when(workflow)
                                                    .cancel();

        assertThatCode(() -> listener.on(event)).doesNotThrowAnyException();
    }

    @Test
    void anUnreachableTemporalIsLoggedNotRethrown() {
        when(client.newUntypedWorkflowStub(workflowId())).thenReturn(workflow);
        doThrow(new IllegalStateException("temporal unavailable")).when(workflow)
                                                                  .cancel();

        assertThatCode(() -> listener.on(event)).doesNotThrowAnyException();
    }

    private String workflowId() {
        return PackingEngineContract.WORKFLOW_ID_PREFIX + event.jobId();
    }
}
