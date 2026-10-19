package com.packing.backend.core.packing;

import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.packing.PackingInputs;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.packing.event.PackingJobFinished;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingJobRecoveryServiceTest {

    private static final Instant NOW      = Instant.parse("2026-08-01T12:00:00Z");
    private static final String  CHECKSUM = "a".repeat(64);
    private static final String  REASON   = "Packing engine stopped responding";

    @Mock
    private PackingJobRepository jobs;

    @Mock
    private DomainEventPublisher events;

    @Captor
    private ArgumentCaptor<Collection<? extends DomainEvent>> published;

    private PackingJobRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new PackingJobRecoveryService(jobs, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void failsAQueuedJobWithoutEngineProvenance() {
        PackingJob job = queuedJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.failStalled(job.id(), REASON);

        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo(REASON);
        assertThat(job.engineVersion()).isNull();
        assertThat(job.engineChecksum()).isNull();
        assertThat(job.finishedAt()).isEqualTo(NOW);
        verify(jobs).save(job);
    }

    @Test
    void failsARunningJobKeepingTheEngineProvenanceItRecorded() {
        PackingJob job = runningJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.failStalled(job.id(), REASON);

        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo(REASON);
        assertThat(job.engineVersion()).isEqualTo("packer 0.1.0");
        assertThat(job.engineChecksum()).isEqualTo(CHECKSUM);
        assertThat(job.startedAt()).isEqualTo(NOW.minusSeconds(10));
        assertThat(job.finishedAt()).isEqualTo(NOW);
        verify(jobs).save(job);
    }

    @Test
    void leavesATerminalJobUnchangedAndNotifiesNobody() {
        PackingJob job = runningJob();
        job.succeed("existing.bin",
                    "application/octet-stream",
                    1,
                    CHECKSUM,
                    "packer 0.1.0",
                    CHECKSUM,
                    NOW.minusSeconds(1));
        job.pullDomainEvents();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.failStalled(job.id(), REASON);

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(job.failureReason()).isNull();
        verify(jobs, never()).save(any());
        verify(events, never()).publishAll(any());
    }

    @Test
    void publishesTheEventThatNotifiesTheRequesterExactlyOnce() {
        PackingJob job = runningJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.failStalled(job.id(), REASON);

        verify(events).publishAll(published.capture());
        assertThat(published.getValue()).singleElement()
                                        .isEqualTo(new PackingJobFinished(job.id(),
                                                                          job.projectId(),
                                                                          job.requestedBy(),
                                                                          PackingJobStatus.FAILED,
                                                                          REASON,
                                                                          null,
                                                                          null,
                                                                          NOW.minusSeconds(10),
                                                                          NOW));
    }

    @Test
    void unknownJobIsReported() {
        PackingJobId unknown = PackingJobId.generate();
        when(jobs.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.failStalled(unknown, REASON)).isInstanceOf(PackingJobNotFoundException.class);

        verify(jobs, never()).save(any());
    }

    @Test
    void isATransactionalServiceBoundary() {
        assertThat(PackingJobRecoveryService.class.isAnnotationPresent(Service.class)).isTrue();
        assertThat(PackingJobRecoveryService.class.isAnnotationPresent(Transactional.class)).isTrue();
    }

    private static PackingJob queuedJob() {
        PackingJob job = PackingJob.queue(PackingJobId.generate(),
                                          ProjectId.generate(),
                                          UserId.generate(),
                                          PackingInputs.single(),
                                          60,
                                          NOW.minusSeconds(30));
        job.pullDomainEvents();
        return job;
    }

    private static PackingJob runningJob() {
        PackingJob job = queuedJob();
        job.markRunning("packer 0.1.0", CHECKSUM, NOW.minusSeconds(10));
        return job;
    }
}
