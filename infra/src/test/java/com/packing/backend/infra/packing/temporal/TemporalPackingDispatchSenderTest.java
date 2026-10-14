package com.packing.backend.infra.packing.temporal;

import com.packing.backend.core.packing.message.PackingDispatchMessage;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineStarted;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.packing.backend.infra.packing.temporal.PackingEngineContract.STATE_TASK_QUEUE;
import static com.packing.backend.infra.packing.temporal.PackingEngineContract.WORKFLOW_ID_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemporalPackingDispatchSenderTest {

    private static final List<PackingJobWorkflow.PackingJobInput> RUNS = new CopyOnWriteArrayList<>();

    private TestWorkflowEnvironment       env;
    private PackingJobRepository          jobs;
    private TemporalPackingDispatchSender sender;
    private PackingJob                    job;

    @BeforeEach
    void setUp() {
        RUNS.clear();
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(STATE_TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(AwaitingWorkflow.class);
        env.start();

        job = PackingJob.queue(PackingJobId.generate(),
                               ProjectId.generate(),
                               UserId.generate(),
                               "{\"testField\":true}",
                               90,
                               Instant.parse("2026-10-14T12:00:00Z"));
        jobs = mock(PackingJobRepository.class);
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));
        sender = new TemporalPackingDispatchSender(env.getWorkflowClient(), jobs);
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void startsOnePackingWorkflowPerJobWithTheJobIdAndRuntime() {
        sender.send(PackingDispatchMessage.versionOne(job.id()));

        DescribeWorkflowExecutionResponse described = describe();
        assertThat(described.getExecutionConfig()
                            .getTaskQueue()
                            .getName()).isEqualTo(STATE_TASK_QUEUE);
        assertThat(described.getWorkflowExecutionInfo()
                            .getType()
                            .getName()).isEqualTo("PackingJob");
        complete();
        assertThat(RUNS).containsExactly(new PackingJobWorkflow.PackingJobInput(job.id()
                                                                                   .toString(),
                                                                                90));
    }

    @Test
    void aSecondDispatchWhileTheWorkflowRunsIsAcceptedWithoutStartingAnother() {
        sender.send(PackingDispatchMessage.versionOne(job.id()));
        sender.send(PackingDispatchMessage.versionOne(job.id()));

        complete();
        assertThat(RUNS).hasSize(1);
    }

    @Test
    void aDispatchAfterTheWorkflowClosedIsRejectedAsADuplicateAndAccepted() {
        sender.send(PackingDispatchMessage.versionOne(job.id()));
        complete();

        sender.send(PackingDispatchMessage.versionOne(job.id()));

        assertThat(RUNS).hasSize(1);
        assertThat(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                                                                                   .isEqualTo(TemporalPackingDispatchSender.REUSE_POLICY);
    }

    @Test
    void anUnknownJobIsReported() {
        PackingJobId unknown = PackingJobId.generate();
        when(jobs.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sender.send(PackingDispatchMessage.versionOne(unknown)))
                                                                                         .isInstanceOf(PackingJobNotFoundException.class);
    }

    private String workflowId() {
        return WORKFLOW_ID_PREFIX + job.id();
    }

    private DescribeWorkflowExecutionResponse describe() {
        return env.getWorkflowServiceStubs()
                  .blockingStub()
                  .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                                                                             .setNamespace(env.getNamespace())
                                                                             .setExecution(WorkflowExecution.newBuilder()
                                                                                                            .setWorkflowId(workflowId()))
                                                                             .build());
    }

    private void complete() {
        env.getWorkflowClient()
           .newWorkflowStub(PackingJobWorkflow.class, workflowId())
           .engineStarted(new EngineStarted(PackingEngineContract.VERSION, "packer 0.3.0", "a".repeat(64)));
        WorkflowStub.fromTyped(env.getWorkflowClient()
                                  .newWorkflowStub(PackingJobWorkflow.class, workflowId()))
                    .getResult(Void.class);
    }

    public static class AwaitingWorkflow implements PackingJobWorkflow {

        private boolean released;

        @Override
        public void run(PackingJobInput input) {
            RUNS.add(input);
            Workflow.await(() -> released);
        }

        @Override
        public void engineStarted(EngineStarted signal) {
            released = true;
        }
    }
}
