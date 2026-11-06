package com.packing.backend.infra.packing.temporal;

import com.packing.backend.core.packing.PackingPreviewService;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.infra.packing.preview.UnrecognizedPackerOutputException;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ActivityImpl(taskQueues = PackingPreviewActivity.TASK_QUEUE)
@RequiredArgsConstructor
public class PackingPreviewActivityImpl implements PackingPreviewActivity {

    static final String   UNRECOGNIZED_OUTPUT = "UnrecognizedPackerOutput";
    static final Duration HEARTBEAT_INTERVAL  = Duration.ofSeconds(10);

    private final PackingPreviewService previews;

    @Override
    public void buildPreview(String jobId) {
        ActivityExecutionContext context = Activity.getExecutionContext();
        ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                                                                                               .daemon()
                                                                                               .name("preview-heartbeat-" + jobId)
                                                                                               .factory());
        heartbeats.scheduleAtFixedRate(() -> context.heartbeat(null),
                                       0,
                                       HEARTBEAT_INTERVAL.toMillis(),
                                       TimeUnit.MILLISECONDS);
        try {
            previews.build(new PackingJobId(UUID.fromString(jobId)));
        } catch (UnrecognizedPackerOutputException unrecognized) {
            throw ApplicationFailure.newNonRetryableFailure(unrecognized.getMessage(), UNRECOGNIZED_OUTPUT);
        } finally {
            heartbeats.shutdownNow();
        }
    }
}
