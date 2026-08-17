package com.packing.backend.core.packing;

import com.packing.backend.core.packing.PackingJobRecoveryService.DispatchStall;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingJobRecoveryServiceTest {

    private static final Instant NOW      = Instant.parse("2026-08-01T12:00:00Z");
    private static final String  CHECKSUM = "a".repeat(64);

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
    void exhaustedDispatchFailsAQueuedJobWithoutEngineProvenance() {
        PackingJob job = queuedJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        assertThat(service.resolveStalledDispatch(job.id(), DispatchStall.DELIVERY_EXHAUSTED)).isTrue();

        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("Dispatch exhausted Service Bus delivery attempts");
        assertThat(job.engineVersion()).isNull();
        assertThat(job.engineChecksum()).isNull();
        assertThat(job.finishedAt()).isEqualTo(NOW);
        verify(jobs).save(job);
    }

    @Test
    void exhaustedDispatchLeavesATerminalJobUnchanged() {
        PackingJob job = runningJob();
        job.succeed("existing.bin",
                    "application/octet-stream",
                    1,
                    CHECKSUM,
                    "packer 0.1.0",
                    CHECKSUM,
                    NOW.minusSeconds(1));
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        assertThat(service.resolveStalledDispatch(job.id(), DispatchStall.DELIVERY_EXHAUSTED)).isTrue();

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(job.failureReason()).isNull();
        verify(jobs, never()).save(job);
    }

    @Test
    void exhaustedDispatchFailsARunningJobKeepingTheEngineProvenanceItRecorded() {
        PackingJob job = runningJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        assertThat(service.resolveStalledDispatch(job.id(), DispatchStall.DELIVERY_EXHAUSTED)).isTrue();

        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("Dispatch exhausted Service Bus delivery attempts");
        assertThat(job.engineVersion()).isEqualTo("packer 0.1.0");
        assertThat(job.engineChecksum()).isEqualTo(CHECKSUM);
        assertThat(job.finishedAt()).isEqualTo(NOW);
        verify(jobs).save(job);
    }

    @Test
    void expiredDispatchLeavesARunningJobForTheWorkerThatMayStillHoldIt() {
        PackingJob job = runningJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        assertThat(service.resolveStalledDispatch(job.id(), DispatchStall.EXPIRED)).isFalse();

        assertThat(job.status()).isEqualTo(PackingJobStatus.RUNNING);
        assertThat(job.failureReason()).isNull();
        assertThat(job.finishedAt()).isNull();
        verify(jobs, never()).save(job);
    }

    @Test
    void expiredDispatchFailsAQueuedJobBecauseNoWorkerEverStartedIt() {
        PackingJob job = queuedJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        assertThat(service.resolveStalledDispatch(job.id(), DispatchStall.EXPIRED)).isTrue();

        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("Dispatch expired before the job started");
        assertThat(job.engineVersion()).isNull();
        assertThat(job.finishedAt()).isEqualTo(NOW);
        verify(jobs).save(job);
    }

    @Test
    void expiredDispatchIsResolvedOnceTheJobReachedATerminalStateByAnotherRoute() {
        PackingJob job = runningJob();
        job.succeed("existing.bin",
                    "application/octet-stream",
                    1,
                    CHECKSUM,
                    "packer 0.1.0",
                    CHECKSUM,
                    NOW.minusSeconds(1));
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        assertThat(service.resolveStalledDispatch(job.id(), DispatchStall.EXPIRED)).isTrue();

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        verify(jobs, never()).save(job);
    }

    @Test
    void looksUpNoResultArtifactInsideTheTransaction() {
        assertThat(PackingJobRecoveryService.class.getDeclaredFields())
                                                                       .noneMatch(field -> PackingJobArtifactStore.class.isAssignableFrom(field.getType()));
    }

    @Test
    void resultArtifactCompletesOnlyARunningJobUsingItsMetadata() {
        PackingJob job = runningJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));
        PackingJobArtifactStore.ResultArtifact artifact = artifact();

        service.recoverResult(job.id(), artifact);

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(job.resultFileName()).isEqualTo("result.bin");
        assertThat(job.resultContentType()).isEqualTo("application/octet-stream");
        assertThat(job.resultSizeBytes()).isEqualTo(42L);
        assertThat(job.resultChecksum()).isEqualTo(CHECKSUM);
        assertThat(job.engineVersion()).isEqualTo("packer 0.2.0");
        assertThat(job.engineChecksum()).isEqualTo(CHECKSUM);
        assertThat(job.finishedAt()).isEqualTo(NOW);
        verify(jobs).save(job);
    }

    @Test
    void resultArtifactLeavesAQueuedJobUnchangedRegardlessOfItsAge() {
        PackingJob job = queuedJob(NOW.minusSeconds(86_400));
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.recoverResult(job.id(), artifact());

        assertThat(job.status()).isEqualTo(PackingJobStatus.QUEUED);
        verify(jobs, never()).save(job);
    }

    @Test
    void stalledResultCompletesAQueuedJobWhoseStartedEventNeverArrived() {
        PackingJob job = queuedJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.recoverStalledResult(job.id(), artifact());

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(job.resultFileName()).isEqualTo("result.bin");
        assertThat(job.resultChecksum()).isEqualTo(CHECKSUM);
        assertThat(job.engineVersion()).isEqualTo("packer 0.2.0");
        assertThat(job.finishedAt()).isEqualTo(NOW);
        verify(jobs).save(job);
    }

    @Test
    void stalledResultCompletesARunningJobUsingItsMetadata() {
        PackingJob job = runningJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.recoverStalledResult(job.id(), artifact());

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(job.engineVersion()).isEqualTo("packer 0.2.0");
        verify(jobs).save(job);
    }

    @Test
    void stalledResultLeavesATerminalJobUnchanged() {
        PackingJob job = runningJob();
        job.succeed("existing.bin",
                    "application/octet-stream",
                    1,
                    CHECKSUM,
                    "packer 0.1.0",
                    CHECKSUM,
                    NOW.minusSeconds(1));
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.recoverStalledResult(job.id(), artifact());

        assertThat(job.resultFileName()).isEqualTo("existing.bin");
        assertThat(job.finishedAt()).isEqualTo(NOW.minusSeconds(1));
        verify(jobs, never()).save(job);
    }

    @Test
    void unknownJobIsReportedForBrokerRedelivery() {
        PackingJobId unknown = PackingJobId.generate();
        when(jobs.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveStalledDispatch(unknown, DispatchStall.DELIVERY_EXHAUSTED))
                                                                                                           .isInstanceOf(PackingJobNotFoundException.class);

        verify(jobs, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failingAStalledDispatchPublishesTheEventThatNotifiesTheRequester() {
        PackingJob job = queuedJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.resolveStalledDispatch(job.id(), DispatchStall.EXPIRED);

        verify(events).publishAll(published.capture());
        assertThat(published.getValue()).singleElement()
                                        .isEqualTo(new PackingJobFinished(job.id(),
                                                                          job.projectId(),
                                                                          job.requestedBy(),
                                                                          PackingJobStatus.FAILED,
                                                                          "Dispatch expired before the job started",
                                                                          null,
                                                                          null,
                                                                          null,
                                                                          NOW));
    }

    @Test
    void recoveringAResultPublishesTheEventThatNotifiesTheRequester() {
        PackingJob job = runningJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.recoverResult(job.id(), artifact());

        verify(events).publishAll(published.capture());
        assertThat(published.getValue()).singleElement()
                                        .isEqualTo(new PackingJobFinished(job.id(),
                                                                          job.projectId(),
                                                                          job.requestedBy(),
                                                                          PackingJobStatus.SUCCEEDED,
                                                                          null,
                                                                          "result.bin",
                                                                          42L,
                                                                          NOW.minusSeconds(10),
                                                                          NOW));
    }

    @Test
    void aRecoveryThatChangesNothingNotifiesNobody() {
        PackingJob job = runningJob();
        job.succeed("existing.bin",
                    "application/octet-stream",
                    1,
                    CHECKSUM,
                    "packer 0.1.0",
                    CHECKSUM,
                    NOW.minusSeconds(1));
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.recoverStalledResult(job.id(), artifact());

        verify(events, never()).publishAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isATransactionalServiceBoundary() {
        assertThat(PackingJobRecoveryService.class.isAnnotationPresent(Service.class)).isTrue();
        assertThat(PackingJobRecoveryService.class.isAnnotationPresent(Transactional.class)).isTrue();
    }

    private static PackingJob queuedJob() {
        return queuedJob(NOW.minusSeconds(30));
    }

    private static PackingJob queuedJob(Instant createdAt) {
        PackingJob job = PackingJob.queue(PackingJobId.generate(),
                                          ProjectId.generate(),
                                          UserId.generate(),
                                          "{\"testField\":true}",
                                          60,
                                          createdAt);
        job.pullDomainEvents();
        return job;
    }

    private static PackingJob runningJob() {
        PackingJob job = queuedJob();
        job.markRunning("packer 0.1.0", CHECKSUM, NOW.minusSeconds(10));
        return job;
    }

    private static PackingJobArtifactStore.ResultArtifact artifact() {
        return new PackingJobArtifactStore.ResultArtifact("result.bin",
                                                          "application/octet-stream",
                                                          42,
                                                          CHECKSUM,
                                                          "packer 0.2.0",
                                                          CHECKSUM);
    }
}
