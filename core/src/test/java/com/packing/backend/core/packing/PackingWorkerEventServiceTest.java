package com.packing.backend.core.packing;

import com.packing.backend.core.packing.message.PackingWorkerEvent;
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
class PackingWorkerEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T11:30:00Z");
    private static final String STARTED_VERSION = "packer 0.1.0";
    private static final String FINAL_VERSION = "packer 0.1.1";
    private static final String STARTED_CHECKSUM = "a".repeat(64);
    private static final String FINAL_CHECKSUM = "b".repeat(64);
    private static final String RESULT_CHECKSUM = "c".repeat(64);

    @Mock
    private PackingJobRepository jobs;

    private PackingWorkerEventService service;

    @BeforeEach
    void setUp() {
        service = new PackingWorkerEventService(jobs, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void startedMovesAQueuedJobToRunningAndPersistsItsProvenance() {
        PackingJob job = queuedJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.apply(started(job.id()));

        assertThat(job.status()).isEqualTo(PackingJobStatus.RUNNING);
        assertThat(job.engineVersion()).isEqualTo(STARTED_VERSION);
        assertThat(job.engineChecksum()).isEqualTo(STARTED_CHECKSUM);
        assertThat(job.startedAt()).isEqualTo(NOW);
        verify(jobs).save(job);
    }

    @Test
    void duplicateStartedDoesNotReplaceTheInitialProvenanceOrPersist() {
        PackingJob job = runningJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.apply(new PackingWorkerEvent.Started(1, job.id(), FINAL_VERSION, FINAL_CHECKSUM));

        assertThat(job.engineVersion()).isEqualTo(STARTED_VERSION);
        assertThat(job.engineChecksum()).isEqualTo(STARTED_CHECKSUM);
        verify(jobs, never()).save(job);
    }

    @Test
    void succeededMovesARunningJobToTerminalAndOverwritesStartedProvenance() {
        PackingJob job = runningJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.apply(succeeded(job.id()));

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(job.engineVersion()).isEqualTo(FINAL_VERSION);
        assertThat(job.engineChecksum()).isEqualTo(FINAL_CHECKSUM);
        assertThat(job.resultFileName()).isEqualTo("result.bin");
        assertThat(job.resultContentType()).isEqualTo("application/octet-stream");
        assertThat(job.resultSizeBytes()).isEqualTo(1234L);
        assertThat(job.resultChecksum()).isEqualTo(RESULT_CHECKSUM);
        assertThat(job.finishedAt()).isEqualTo(NOW);
        verify(jobs).save(job);
    }

    @Test
    void failedMovesARunningJobToTerminalAndOverwritesStartedProvenance() {
        PackingJob job = runningJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.apply(failed(job.id()));

        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("engine exited 1");
        assertThat(job.engineVersion()).isEqualTo(FINAL_VERSION);
        assertThat(job.engineChecksum()).isEqualTo(FINAL_CHECKSUM);
        assertThat(job.finishedAt()).isEqualTo(NOW);
        verify(jobs).save(job);
    }

    @Test
    void terminalSuccessCanArriveBeforeStarted() {
        PackingJob job = queuedJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.apply(succeeded(job.id()));

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(job.engineVersion()).isEqualTo(FINAL_VERSION);
        verify(jobs).save(job);
    }

    @Test
    void terminalFailureCanArriveBeforeStarted() {
        PackingJob job = queuedJob();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.apply(failed(job.id()));

        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.engineVersion()).isEqualTo(FINAL_VERSION);
        verify(jobs).save(job);
    }

    @Test
    void anyWorkerEventAfterTerminalSuccessIsANoOp() {
        PackingJob job = runningJob();
        job.succeed("earlier.bin", "application/octet-stream", 1, "d".repeat(64),
                STARTED_VERSION, STARTED_CHECKSUM, NOW.minusSeconds(1));
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.apply(failed(job.id()));

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(job.resultFileName()).isEqualTo("earlier.bin");
        verify(jobs, never()).save(job);
    }

    @Test
    void anyWorkerEventAfterTerminalFailureIsANoOp() {
        PackingJob job = runningJob();
        job.fail("earlier failure", STARTED_VERSION, STARTED_CHECKSUM, NOW.minusSeconds(1));
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.apply(succeeded(job.id()));

        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("earlier failure");
        verify(jobs, never()).save(job);
    }

    @Test
    void startedAfterTerminalFailureIsAlsoANoOp() {
        PackingJob job = runningJob();
        job.fail("earlier failure", STARTED_VERSION, STARTED_CHECKSUM, NOW.minusSeconds(1));
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        service.apply(new PackingWorkerEvent.Started(1, job.id(), FINAL_VERSION, FINAL_CHECKSUM));

        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.engineVersion()).isEqualTo(STARTED_VERSION);
        verify(jobs, never()).save(job);
    }

    @Test
    void unknownJobIsReportedSoTheBrokerCanRetry() {
        PackingJobId jobId = PackingJobId.generate();
        when(jobs.findById(jobId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(started(jobId)))
                .isInstanceOf(PackingJobNotFoundException.class);

        verify(jobs, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isATransactionalServiceBoundary() {
        assertThat(PackingWorkerEventService.class.isAnnotationPresent(Service.class)).isTrue();
        assertThat(PackingWorkerEventService.class.isAnnotationPresent(Transactional.class)).isTrue();
    }

    private static PackingJob queuedJob() {
        return PackingJob.queue(PackingJobId.generate(), ProjectId.generate(), UserId.generate(),
                "{\"testField\":true}", 60, NOW.minusSeconds(30));
    }

    private static PackingJob runningJob() {
        PackingJob job = queuedJob();
        job.markRunning(STARTED_VERSION, STARTED_CHECKSUM, NOW.minusSeconds(1));
        return job;
    }

    private static PackingWorkerEvent.Started started(PackingJobId jobId) {
        return new PackingWorkerEvent.Started(1, jobId, STARTED_VERSION, STARTED_CHECKSUM);
    }

    private static PackingWorkerEvent.Succeeded succeeded(PackingJobId jobId) {
        return new PackingWorkerEvent.Succeeded(1, jobId, FINAL_VERSION, FINAL_CHECKSUM,
                "result.bin", "application/octet-stream", 1234, RESULT_CHECKSUM);
    }

    private static PackingWorkerEvent.Failed failed(PackingJobId jobId) {
        return new PackingWorkerEvent.Failed(1, jobId, FINAL_VERSION, FINAL_CHECKSUM,
                "engine exited 1");
    }
}
