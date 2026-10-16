package com.packing.backend.infra.packing.temporal;

import com.packing.backend.core.packing.PackingJobRecoveryService;
import com.packing.backend.core.packing.PackingWorkerEventService;
import com.packing.backend.core.packing.message.PackingWorkerEvent;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineFailureDetails;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineResult;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineStarted;
import io.temporal.spring.boot.ActivityImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static com.packing.backend.infra.packing.temporal.PackingEngineContract.VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PackingStateActivitiesImplTest {

    private static final String       JOB      = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final PackingJobId JOB_ID   = new PackingJobId(UUID.fromString(JOB));
    private static final String       CHECKSUM = "a".repeat(64);

    @Mock
    private PackingWorkerEventService workerEvents;

    @Mock
    private PackingJobRecoveryService recovery;

    @InjectMocks
    private PackingStateActivitiesImpl activities;

    @Test
    void recordsStartedWithTheEngineProvenance() {
        activities.recordStarted(JOB, new EngineStarted(VERSION, "packer 0.3.0", CHECKSUM));

        verify(workerEvents).apply(new PackingWorkerEvent.Started(VERSION, JOB_ID, "packer 0.3.0", CHECKSUM));
    }

    @Test
    void recordsSucceededWithTheResultMetadata() {
        activities.recordSucceeded(JOB,
                                   new EngineResult(VERSION,
                                                    "packer 0.3.0",
                                                    CHECKSUM,
                                                    "output",
                                                    "application/json",
                                                    12,
                                                    "b".repeat(64)));

        verify(workerEvents).apply(new PackingWorkerEvent.Succeeded(VERSION,
                                                                    JOB_ID,
                                                                    "packer 0.3.0",
                                                                    CHECKSUM,
                                                                    "output",
                                                                    "application/json",
                                                                    12,
                                                                    "b".repeat(64)));
    }

    @Test
    void recordsFailedWithTheEngineReason() {
        activities.recordFailed(JOB, new EngineFailureDetails(VERSION, "packer 0.3.0", CHECKSUM, "packer exited with code 2"));

        verify(workerEvents).apply(new PackingWorkerEvent.Failed(VERSION,
                                                                 JOB_ID,
                                                                 "packer 0.3.0",
                                                                 CHECKSUM,
                                                                 "packer exited with code 2"));
    }

    @Test
    void recordsStalledThroughRecovery() {
        activities.recordStalled(JOB, "Packing engine stopped responding");

        verify(recovery).failStalled(JOB_ID, "Packing engine stopped responding");
    }

    @Test
    void isDiscoveredOnTheStateTaskQueue() {
        assertThat(PackingStateActivitiesImpl.class.getAnnotation(ActivityImpl.class)
                                                   .taskQueues()).containsExactly(PackingEngineContract.STATE_TASK_QUEUE);
    }
}
