package com.packing.backend.infra.packing.temporal;

import com.packing.backend.core.packing.PackingPreviewService;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.infra.packing.preview.UnrecognizedPackerOutputException;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PackingPreviewActivityImplTest {

    private static final String       JOB    = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final PackingJobId JOB_ID = new PackingJobId(UUID.fromString(JOB));

    private final PackingPreviewService previews = mock(PackingPreviewService.class);

    private TestActivityEnvironment env;
    private PackingPreviewActivity  activity;

    @BeforeEach
    void setUp() {
        env = TestActivityEnvironment.newInstance();
        env.registerActivitiesImplementations(new PackingPreviewActivityImpl(previews));
        activity = env.newActivityStub(PackingPreviewActivity.class,
                                       ActivityOptions.newBuilder()
                                                      .setStartToCloseTimeout(Duration.ofMinutes(1))
                                                      .setHeartbeatTimeout(Duration.ofMinutes(1))
                                                      .build());
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void buildsThePreviewOfTheJobAndHeartbeatsWhileItRuns() {
        AtomicInteger heartbeats = new AtomicInteger();
        env.setActivityHeartbeatListener(Object.class, details -> heartbeats.incrementAndGet());
        doAnswer(invocation -> {
            Thread.sleep(200);
            return null;
        }).when(previews)
          .build(JOB_ID);

        activity.buildPreview(JOB);

        verify(previews).build(JOB_ID);
        assertThat(heartbeats.get()).isPositive();
    }

    @Test
    void anUnrecognizedResultIsNotWorthRetrying() {
        doThrow(new UnrecognizedPackerOutputException("copy 2 of 'bunny.obj' is not a rotated and moved copy")).when(previews)
                                                                                                               .build(JOB_ID);

        assertThatThrownBy(() -> activity.buildPreview(JOB)).isInstanceOf(ActivityFailure.class)
                                                            .cause()
                                                            .isInstanceOfSatisfying(ApplicationFailure.class, failure -> {
                                                                assertThat(failure.isNonRetryable()).isTrue();
                                                                assertThat(failure.getType()).isEqualTo(PackingPreviewActivityImpl.UNRECOGNIZED_OUTPUT);
                                                            });
    }
}
