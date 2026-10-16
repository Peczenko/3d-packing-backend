package com.packing.backend.infra.packing.temporal;

import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineFailureDetails;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineResult;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineStarted;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.RunEngineInput;
import com.packing.backend.infra.packing.temporal.PackingJobWorkflow.PackingJobInput;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.packing.backend.infra.packing.temporal.PackingEngineContract.ENGINE_FAILURE;
import static com.packing.backend.infra.packing.temporal.PackingEngineContract.ENGINE_TASK_QUEUE;
import static com.packing.backend.infra.packing.temporal.PackingEngineContract.STATE_TASK_QUEUE;
import static com.packing.backend.infra.packing.temporal.PackingEngineContract.VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

class PackingJobWorkflowImplTest {

    private static final String        JOB_ID   = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final String        CHECKSUM = "a".repeat(64);
    private static final EngineStarted STARTED  = new EngineStarted(VERSION, "packer 0.3.0", CHECKSUM);
    private static final EngineResult  RESULT   = new EngineResult(VERSION,
                                                                   "packer 0.3.0",
                                                                   CHECKSUM,
                                                                   "output",
                                                                   "application/json",
                                                                   12,
                                                                   "b".repeat(64));

    private final List<RunEngineInput> engineInputs = new CopyOnWriteArrayList<>();

    private TestWorkflowEnvironment env;
    private PackingStateActivities  state;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        state = mock(PackingStateActivities.class, withSettings().withoutAnnotations());
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void recordsStartedBeforeSucceededAndPassesOnlyTheJobIdToTheEngine() {
        runJob(input -> {
            signalStarted(STARTED);
            return RESULT;
        });

        InOrder order = inOrder(state);
        order.verify(state)
             .recordStarted(JOB_ID, STARTED);
        order.verify(state)
             .recordSucceeded(JOB_ID, RESULT);
        verify(state, never()).recordFailed(anyString(), any());
        verify(state, never()).recordStalled(anyString(), anyString());
        assertThat(engineInputs).containsExactly(new RunEngineInput(VERSION, JOB_ID));
    }

    @Test
    void engineFailureIsRecordedWithItsDetailsAndNeverRetried() {
        EngineFailureDetails details = new EngineFailureDetails(VERSION, "packer 0.3.0", CHECKSUM, "packer exited with code 2");

        runJob(input -> {
            signalStarted(STARTED);
            throw ApplicationFailure.newFailure("packer exited with code 2", ENGINE_FAILURE, details);
        });

        InOrder order = inOrder(state);
        order.verify(state)
             .recordStarted(JOB_ID, STARTED);
        order.verify(state)
             .recordFailed(JOB_ID, details);
        verify(state, never()).recordStalled(anyString(), anyString());
        assertThat(engineInputs).hasSize(1);
    }

    @Test
    void retryableFailuresExhaustThreeAttemptsAndMarkTheJobStalled() {
        runJob(input -> {
            throw new IllegalStateException("blob storage unavailable");
        });

        verify(state).recordStalled(JOB_ID, PackingJobWorkflowImpl.COULD_NOT_COMPLETE);
        verify(state, never()).recordStarted(anyString(), any());
        verify(state, never()).recordSucceeded(anyString(), any());
        assertThat(engineInputs).hasSize(PackingJobWorkflowImpl.ENGINE_ATTEMPTS);
    }

    @Test
    void aSlowStartedWriteStillLandsBeforeTheTerminalWrite() {
        doThrow(new IllegalStateException("database unavailable"))
                                                                  .doThrow(new IllegalStateException("database unavailable"))
                                                                  .doNothing()
                                                                  .when(state)
                                                                  .recordStarted(JOB_ID, STARTED);

        runJob(input -> {
            signalStarted(STARTED);
            return RESULT;
        });

        InOrder order = inOrder(state);
        order.verify(state, times(3))
             .recordStarted(JOB_ID, STARTED);
        order.verify(state)
             .recordSucceeded(JOB_ID, RESULT);
    }

    @Test
    void aRepeatedStartedSignalIsRecordedOnce() {
        runJob(input -> {
            signalStarted(STARTED);
            signalStarted(STARTED);
            return RESULT;
        });

        verify(state, times(1)).recordStarted(JOB_ID, STARTED);
        verify(state).recordSucceeded(JOB_ID, RESULT);
    }

    @Test
    void retriedEngineAttemptsSignalAgainButStartedIsRecordedOnce() {
        doNothing().when(state)
                   .recordStarted(JOB_ID, STARTED);

        runJob(input -> {
            signalStarted(STARTED);
            if (Activity.getExecutionContext()
                        .getInfo()
                        .getAttempt()
                    == 1) {
                throw new IllegalStateException("worker lost");
            }
            return RESULT;
        });

        verify(state, times(1)).recordStarted(JOB_ID, STARTED);
        verify(state).recordSucceeded(JOB_ID, RESULT);
        assertThat(engineInputs).hasSize(2);
    }

    @Test
    void engineOptionsBoundTheEngineByItsRuntimePlusTransferMargin() {
        ActivityOptions options = PackingJobWorkflowImpl.engineOptions(120);

        assertThat(options.getTaskQueue()).isEqualTo(ENGINE_TASK_QUEUE);
        assertThat(options.getStartToCloseTimeout()).isEqualTo(Duration.ofSeconds(120)
                                                                       .plus(PackingJobWorkflowImpl.TRANSFER_MARGIN));
        assertThat(options.getHeartbeatTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(options.getRetryOptions()
                          .getMaximumAttempts()).isEqualTo(3);
        assertThat(options.getRetryOptions()
                          .getDoNotRetry()).containsExactly(ENGINE_FAILURE);
    }

    @Test
    void stallReasonsDistinguishASilentEngineFromAnOverrunningOne() {
        assertThat(PackingJobWorkflowImpl.stallReason(failure(new TimeoutFailure("timeout", null, TimeoutType.TIMEOUT_TYPE_HEARTBEAT))))
                                                                                                                                        .isEqualTo(PackingJobWorkflowImpl.STOPPED_RESPONDING);
        assertThat(PackingJobWorkflowImpl.stallReason(failure(new TimeoutFailure("timeout", null, TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE))))
                                                                                                                                             .isEqualTo(PackingJobWorkflowImpl.EXCEEDED_TIME_LIMIT);
        assertThat(PackingJobWorkflowImpl.stallReason(failure(ApplicationFailure.newFailure("boom", "IOException"))))
                                                                                                                     .isEqualTo(PackingJobWorkflowImpl.COULD_NOT_COMPLETE);
    }

    private static ActivityFailure failure(RuntimeException cause) {
        return new ActivityFailure("activity failed",
                                   1,
                                   2,
                                   "RunEngine",
                                   "1",
                                   RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED,
                                   "worker",
                                   cause);
    }

    private void runJob(Engine engine) {
        Worker stateWorker = env.newWorker(STATE_TASK_QUEUE);
        stateWorker.registerWorkflowImplementationTypes(PackingJobWorkflowImpl.class);
        stateWorker.registerActivitiesImplementations(state);
        Worker engineWorker = env.newWorker(ENGINE_TASK_QUEUE);
        engineWorker.registerActivitiesImplementations((PackingEngineActivity) input -> {
            engineInputs.add(input);
            return engine.run(input);
        });
        env.start();

        PackingJobWorkflow workflow = env.getWorkflowClient()
                                         .newWorkflowStub(PackingJobWorkflow.class,
                                                          WorkflowOptions.newBuilder()
                                                                         .setWorkflowId(PackingEngineContract.WORKFLOW_ID_PREFIX + JOB_ID)
                                                                         .setTaskQueue(STATE_TASK_QUEUE)
                                                                         .build());
        workflow.run(new PackingJobInput(JOB_ID, 60));
    }

    private void signalStarted(EngineStarted started) {
        String workflowId = Activity.getExecutionContext()
                                    .getInfo()
                                    .getWorkflowId();
        env.getWorkflowClient()
           .newWorkflowStub(PackingJobWorkflow.class, workflowId)
           .engineStarted(started);
    }

    @FunctionalInterface
    private interface Engine {

        EngineResult run(RunEngineInput input);
    }
}
