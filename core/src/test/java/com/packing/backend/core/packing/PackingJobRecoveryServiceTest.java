package com.packing.backend.core.packing;

import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingJobRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final String CHECKSUM = "a".repeat(64);

    @Mock
    private PackingJobRepository jobs;

    private PackingJobRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new PackingJobRecoveryService(jobs, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void exhaustedDispatchFailsAQueuedJobWithoutEngineProvenance() {
        PackingJob job = queuedJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.failExhaustedDispatch(job.id());

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
        job.succeed("existing.bin", "application/octet-stream", 1, CHECKSUM,
                "packer 0.1.0", CHECKSUM, NOW.minusSeconds(1));
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.failExhaustedDispatch(job.id());

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(job.failureReason()).isNull();
        verify(jobs, never()).save(job);
    }

    @Test
    void exhaustedDispatchFailsARunningJobKeepingTheEngineProvenanceItRecorded() {
        PackingJob job = runningJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.failExhaustedDispatch(job.id());

        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("Dispatch exhausted Service Bus delivery attempts");
        assertThat(job.engineVersion()).isEqualTo("packer 0.1.0");
        assertThat(job.engineChecksum()).isEqualTo(CHECKSUM);
        assertThat(job.finishedAt()).isEqualTo(NOW);
        verify(jobs).save(job);
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
    void unknownJobIsReportedForBrokerRedelivery() {
        PackingJobId unknown = PackingJobId.generate();
        when(jobs.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.failExhaustedDispatch(unknown))
                .isInstanceOf(PackingJobNotFoundException.class);

        verify(jobs, never()).save(org.mockito.ArgumentMatchers.any());
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
        return PackingJob.queue(PackingJobId.generate(), ProjectId.generate(), UserId.generate(),
                "{\"testField\":true}", 60, createdAt);
    }

    private static PackingJob runningJob() {
        PackingJob job = queuedJob();
        job.markRunning("packer 0.1.0", CHECKSUM, NOW.minusSeconds(10));
        return job;
    }

    private static PackingJobArtifactStore.ResultArtifact artifact() {
        return new PackingJobArtifactStore.ResultArtifact("result.bin", "application/octet-stream", 42,
                CHECKSUM, "packer 0.2.0", CHECKSUM);
    }
}
