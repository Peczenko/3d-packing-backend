package com.packing.backend.infra.packing.temporal;

import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineFailureDetails;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineResult;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineStarted;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.RunEngineInput;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.Optional;

import static com.packing.backend.infra.packing.temporal.PackingEngineContract.ENGINE_FAILURE;
import static com.packing.backend.infra.packing.temporal.PackingEngineContract.ENGINE_TASK_QUEUE;
import static com.packing.backend.infra.packing.temporal.PackingEngineContract.VERSION;

@WorkflowImpl(taskQueues = PackingEngineContract.STATE_TASK_QUEUE)
public class PackingJobWorkflowImpl implements PackingJobWorkflow {

    static final Duration TRANSFER_MARGIN     = Duration.ofMinutes(5);
    static final Duration HEARTBEAT_TIMEOUT   = Duration.ofSeconds(60);
    static final int      ENGINE_ATTEMPTS     = 3;
    static final String   STOPPED_RESPONDING  = "Packing engine stopped responding";
    static final String   EXCEEDED_TIME_LIMIT = "Packing engine exceeded its time limit";
    static final String   COULD_NOT_COMPLETE  = "Packing engine could not complete the job";

    private static final ActivityOptions STATE_OPTIONS = ActivityOptions.newBuilder()
                                                                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                                                                        .setRetryOptions(RetryOptions.newBuilder()
                                                                                                     .setInitialInterval(Duration.ofSeconds(1))
                                                                                                     .setBackoffCoefficient(2)
                                                                                                     .setMaximumInterval(Duration.ofMinutes(1))
                                                                                                     .build())
                                                                        .build();

    private final PackingStateActivities state = Workflow.newActivityStub(PackingStateActivities.class, STATE_OPTIONS);

    private String        jobId;
    private Promise<Void> started;

    @Override
    public void run(PackingJobInput input) {
        jobId = input.jobId();
        PackingEngineActivity engine = Workflow.newActivityStub(PackingEngineActivity.class,
                                                                engineOptions(input.maxRuntimeSeconds()));
        EngineResult result;
        try {
            result = engine.runEngine(new RunEngineInput(VERSION, jobId));
        } catch (ActivityFailure failure) {
            awaitStarted();
            Optional<EngineFailureDetails> engineFailure = engineFailure(failure);
            if (engineFailure.isPresent()) {
                state.recordFailed(jobId, engineFailure.get());
            } else {
                state.recordStalled(jobId, stallReason(failure));
            }
            return;
        }
        awaitStarted();
        state.recordSucceeded(jobId, result);
    }

    @Override
    public void engineStarted(EngineStarted signal) {
        if (started == null) {
            started = Async.procedure(state::recordStarted, jobId, signal);
        }
    }

    private void awaitStarted() {
        if (started != null) {
            started.get();
        }
    }

    static ActivityOptions engineOptions(long maxRuntimeSeconds) {
        return ActivityOptions.newBuilder()
                              .setTaskQueue(ENGINE_TASK_QUEUE)
                              .setStartToCloseTimeout(Duration.ofSeconds(maxRuntimeSeconds)
                                                              .plus(TRANSFER_MARGIN))
                              .setHeartbeatTimeout(HEARTBEAT_TIMEOUT)
                              .setRetryOptions(RetryOptions.newBuilder()
                                                           .setMaximumAttempts(ENGINE_ATTEMPTS)
                                                           .setInitialInterval(Duration.ofSeconds(5))
                                                           .setBackoffCoefficient(2)
                                                           .setDoNotRetry(ENGINE_FAILURE)
                                                           .build())
                              .build();
    }

    static Optional<EngineFailureDetails> engineFailure(ActivityFailure failure) {
        if (failure.getCause() instanceof ApplicationFailure application
                && ENGINE_FAILURE.equals(application.getType())
                && application.getDetails()
                              .getSize()
                        > 0) {
            return Optional.of(application.getDetails()
                                          .get(EngineFailureDetails.class));
        }
        return Optional.empty();
    }

    static String stallReason(ActivityFailure failure) {
        if (failure.getCause() instanceof TimeoutFailure timeout) {
            return switch (timeout.getTimeoutType()) {
                case TIMEOUT_TYPE_HEARTBEAT -> STOPPED_RESPONDING;
                case TIMEOUT_TYPE_START_TO_CLOSE, TIMEOUT_TYPE_SCHEDULE_TO_CLOSE -> EXCEEDED_TIME_LIMIT;
                default -> COULD_NOT_COMPLETE;
            };
        }
        return COULD_NOT_COMPLETE;
    }
}
