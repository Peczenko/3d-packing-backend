package com.packing.backend.domain.packing;

import com.packing.backend.domain.packing.event.PackingJobQueued;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackingJobTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:15:30Z");
    private static final PackingJobId JOB_ID = PackingJobId.generate();
    private static final ProjectId PROJECT_ID = ProjectId.generate();
    private static final UserId USER_ID = UserId.generate();
    private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void queuesOpaqueJsonAndRaisesOneEvent() {
        PackingJob job = PackingJob.queue(JOB_ID, PROJECT_ID, USER_ID,
                "{\"testField\":42}", 60, NOW);

        assertThat(job.status()).isEqualTo(PackingJobStatus.QUEUED);
        assertThat(job.specJson()).isEqualTo("{\"testField\":42}");
        assertThat(job.version()).isZero();
        assertThat(job.domainEvents()).containsExactly(
                new PackingJobQueued(JOB_ID, PROJECT_ID, NOW));
    }

    @Test
    void followsQueuedRunningSucceededLifecycleAndRecordsFinalProvenance() {
        PackingJob job = queuedJob();
        job.markDispatched(NOW.plusSeconds(1));
        job.markRunning("packer 0.3.0", SHA_A, NOW.plusSeconds(2));
        job.succeed("output.bin", "application/octet-stream", 12, SHA_B,
                "packer 0.3.0", SHA_A, NOW.plusSeconds(3));

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(job.engineVersion()).isEqualTo("packer 0.3.0");
        assertThat(job.engineChecksum()).isEqualTo(SHA_A);
        assertThat(job.resultFileName()).isEqualTo("output.bin");
        assertThat(job.resultContentType()).isEqualTo("application/octet-stream");
        assertThat(job.resultSizeBytes()).isEqualTo(12L);
        assertThat(job.resultChecksum()).isEqualTo(SHA_B);
        assertThat(job.finishedAt()).isEqualTo(NOW.plusSeconds(3));
    }

    @Test
    void succeedsQueuedJobWhenStartedMessageArrivesLate() {
        PackingJob job = queuedJob();

        assertThat(job.succeed("output.bin", "application/octet-stream", 12, SHA_B,
                "packer 0.3.0", SHA_A, NOW.plusSeconds(1))).isTrue();

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(job.finishedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void dispatchIsRecordedOnlyOnceWithoutChangingStatus() {
        PackingJob job = queuedJob();

        assertThat(job.markDispatched(NOW)).isTrue();
        assertThat(job.markDispatched(NOW.plusSeconds(1))).isFalse();
        assertThat(job.status()).isEqualTo(PackingJobStatus.QUEUED);
        assertThat(job.dispatchedAt()).isEqualTo(NOW);
    }

    @Test
    void duplicateRunningTransitionIsANoOp() {
        PackingJob job = queuedJob();

        assertThat(job.markRunning("packer 0.3.0", SHA_A, NOW)).isTrue();
        assertThat(job.markRunning("different", SHA_B, NOW.plusSeconds(1))).isFalse();
        assertThat(job.engineVersion()).isEqualTo("packer 0.3.0");
        assertThat(job.startedAt()).isEqualTo(NOW);
    }

    @Test
    void failureRecordsEngineProvenanceAndIsIdempotent() {
        PackingJob job = queuedJob();

        assertThat(job.fail("engine exited", "packer 0.3.0", SHA_A, NOW)).isTrue();
        assertThat(job.fail("different", "other", SHA_B, NOW.plusSeconds(1))).isFalse();
        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("engine exited");
        assertThat(job.engineVersion()).isEqualTo("packer 0.3.0");
        assertThat(job.finishedAt()).isEqualTo(NOW);
    }

    @Test
    void failureBeforeStartLeavesEngineProvenanceEmpty() {
        PackingJob job = queuedJob();

        assertThat(job.failBeforeStart("dead-lettered", NOW)).isTrue();
        assertThat(job.failBeforeStart("different reason", NOW.plusSeconds(1))).isFalse();
        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.engineVersion()).isNull();
        assertThat(job.engineChecksum()).isNull();
        assertThat(job.failureReason()).isEqualTo("dead-lettered");
    }

    @Test
    void duplicateSuccessAtDifferentTimeDoesNotReplaceTerminalData() {
        PackingJob job = succeededJob();
        Instant originalFinishedAt = job.finishedAt();

        assertThat(job.succeed("output.bin", "application/octet-stream", 12, SHA_B,
                "packer 0.3.0", SHA_A, NOW.plusSeconds(3))).isFalse();

        assertThat(job.finishedAt()).isEqualTo(originalFinishedAt);
        assertThat(job.resultFileName()).isEqualTo("output.bin");
        assertThat(job.resultChecksum()).isEqualTo(SHA_B);
    }

    @Test
    void ignoresDifferentLateResultMessagesAfterSucceededWithoutChangingTerminalData() {
        PackingJob job = succeededJob();
        Instant originalFinishedAt = job.finishedAt();

        assertThat(job.succeed("different.bin", "application/octet-stream", 99, SHA_A,
                "different", SHA_B, NOW.plusSeconds(3))).isFalse();
        assertThat(job.fail("different failure", "different", SHA_B, NOW.plusSeconds(4))).isFalse();
        assertThat(job.failBeforeStart("different failure", NOW.plusSeconds(5))).isFalse();

        assertThat(job.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(job.finishedAt()).isEqualTo(originalFinishedAt);
        assertThat(job.resultFileName()).isEqualTo("output.bin");
        assertThat(job.resultChecksum()).isEqualTo(SHA_B);
        assertThat(job.failureReason()).isNull();
    }

    @Test
    void ignoresDifferentLateResultMessagesAfterFailedWithoutChangingTerminalData() {
        PackingJob job = failedJob();
        Instant originalFinishedAt = job.finishedAt();

        assertThat(job.succeed("output.bin", "application/octet-stream", 12, SHA_B,
                "packer 0.3.0", SHA_A, NOW.plusSeconds(3))).isFalse();
        assertThat(job.fail("different failure", "different", SHA_B, NOW.plusSeconds(4))).isFalse();
        assertThat(job.failBeforeStart("different failure", NOW.plusSeconds(5))).isFalse();

        assertThat(job.status()).isEqualTo(PackingJobStatus.FAILED);
        assertThat(job.finishedAt()).isEqualTo(originalFinishedAt);
        assertThat(job.failureReason()).isEqualTo("engine exited");
        assertThat(job.resultFileName()).isNull();
        assertThat(job.engineVersion()).isEqualTo("packer 0.3.0");
    }

    @Test
    void ignoresLateStartedMessagesAfterTerminalJobs() {
        PackingJob succeeded = succeededJob();
        PackingJob failed = failedJob();

        assertThat(succeeded.markRunning("different", SHA_B, NOW.plusSeconds(3))).isFalse();
        assertThat(failed.markRunning("different", SHA_B, NOW.plusSeconds(3))).isFalse();

        assertThat(succeeded.engineVersion()).isEqualTo("packer 0.3.0");
        assertThat(succeeded.startedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(failed.engineVersion()).isEqualTo("packer 0.3.0");
        assertThat(failed.startedAt()).isNull();
    }

    @Test
    void normalizesUppercaseEngineAndResultChecksums() {
        PackingJob job = queuedJob();

        job.markRunning("packer 0.3.0", SHA_A.toUpperCase(), NOW.plusSeconds(1));
        job.succeed("output.bin", "application/octet-stream", 12, SHA_B.toUpperCase(),
                "packer 0.3.0", SHA_A.toUpperCase(), NOW.plusSeconds(2));

        assertThat(job.engineChecksum()).isEqualTo(SHA_A);
        assertThat(job.resultChecksum()).isEqualTo(SHA_B);
    }

    @Test
    void queueRejectsBlankJsonAndOutOfRangeRuntime() {
        assertThatThrownBy(() -> PackingJob.queue(JOB_ID, PROJECT_ID, USER_ID, " ", 60, NOW))
                .isInstanceOf(DomainRuleViolationException.class);
        assertThatThrownBy(() -> PackingJob.queue(JOB_ID, PROJECT_ID, USER_ID, "{}", 0, NOW))
                .isInstanceOf(DomainRuleViolationException.class);
        assertThatThrownBy(() -> PackingJob.queue(JOB_ID, PROJECT_ID, USER_ID, "{}", 7_201, NOW))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void rehydrateRestoresStateWithoutRecordingEventsAndCanAdvanceVersion() {
        PackingJob job = PackingJob.rehydrate(JOB_ID, PROJECT_ID, USER_ID, "{}", 60,
                PackingJobStatus.FAILED, "packer 0.3.0", SHA_A, NOW, NOW.plusSeconds(1), NOW.plusSeconds(2),
                null, null, null, null, "engine exited", 7, NOW);

        assertThat(job.domainEvents()).isEmpty();
        assertThat(job.version()).isEqualTo(7);
        job.markPersisted();
        assertThat(job.version()).isEqualTo(8);
    }

    private PackingJob queuedJob() {
        return PackingJob.queue(JOB_ID, PROJECT_ID, USER_ID, "{}", 60, NOW);
    }

    private PackingJob succeededJob() {
        PackingJob job = queuedJob();
        job.markRunning("packer 0.3.0", SHA_A, NOW.plusSeconds(1));
        job.succeed("output.bin", "application/octet-stream", 12, SHA_B,
                "packer 0.3.0", SHA_A, NOW.plusSeconds(2));
        return job;
    }

    private PackingJob failedJob() {
        PackingJob job = queuedJob();
        job.fail("engine exited", "packer 0.3.0", SHA_A, NOW.plusSeconds(1));
        return job;
    }
}
