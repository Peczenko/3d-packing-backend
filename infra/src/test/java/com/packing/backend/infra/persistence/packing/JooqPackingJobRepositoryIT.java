package com.packing.backend.infra.persistence.packing;

import com.packing.backend.core.shared.ConcurrentUpdateException;
import com.packing.backend.domain.packing.PackingInput;
import com.packing.backend.domain.packing.PackingInputs;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.packing.PackingParameters;
import com.packing.backend.domain.packing.PackingPreview;
import com.packing.backend.domain.packing.PreviewModel;
import com.packing.backend.domain.packing.PreviewQuality;
import com.packing.backend.domain.packing.ResultBin;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;
import com.packing.backend.infra.TestcontainersConfiguration;
import com.packing.backend.infra.persistence.project.JooqProjectRepository;
import com.packing.backend.infra.persistence.shared.AggregateWriter;
import com.packing.backend.infra.persistence.user.JooqUserRepository;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.packing.backend.infra.persistence.jooq.tables.PackingJobs.PACKING_JOBS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JooqTest
@Import(TestcontainersConfiguration.class)
class JooqPackingJobRepositoryIT {

    private static final String ENGINE_CHECKSUM = "d3a15aa3cd30cc79123d6a50d2809ed794a452e67fa857bbc7ac343cbfca9971";
    private static final String RESULT_CHECKSUM = "e4b26bb4de41dd8a234e7b61e3910fe8a5b563f78ab968ccd8bd454dcadb1082";

    @Autowired
    private DSLContext dsl;

    private UserId    user;
    private ProjectId project;

    @BeforeEach
    void createUserAndProject() {
        Instant now = now();
        User owner = User.register(new FirebaseUid("packing-owner"),
                                   new Email("packing-owner@example.com"),
                                   new Username("packing-owner"),
                                   "Packing Owner",
                                   now);
        new JooqUserRepository(dsl, new AggregateWriter(dsl)).save(owner);
        user = owner.id();

        Project owned = Project.create(new ProjectName("Packing Project"), user, now);
        new JooqProjectRepository(dsl, new AggregateWriter(dsl)).save(owned);
        project = owned.id();
    }

    @Test
    void savesQueuedJobThenPersistsRunningProvenanceAndVersion() throws Exception {
        PackingJob job = queuedJob(now());

        repository().save(job);
        PackingJob reloaded = repository().findById(job.id())
                                          .orElseThrow();
        assertThat(reloaded.input()).isEqualTo(job.input());
        reloaded.markRunning("packer 0.3.0", ENGINE_CHECKSUM, now());
        repository().save(reloaded);

        assertThat(reloaded.version()).isEqualTo(2L);
        assertThat(repository().findById(job.id())).hasValueSatisfying(found -> {
            assertThat(found.status()).isEqualTo(PackingJobStatus.RUNNING);
            assertThat(found.engineVersion()).isEqualTo("packer 0.3.0");
            assertThat(found.engineChecksum()).isEqualTo(ENGINE_CHECKSUM);
            assertThat(found.version()).isEqualTo(2L);
        });
    }

    @Test
    void persistsACancelledJob() {
        PackingJob job = queuedJob(now());
        repository().save(job);
        PackingJob reloaded = repository().findById(job.id())
                                          .orElseThrow();
        Instant cancelledAt = now();

        reloaded.cancel(cancelledAt);
        repository().save(reloaded);

        assertThat(repository().findById(job.id())).hasValueSatisfying(found -> {
            assertThat(found.status()).isEqualTo(PackingJobStatus.CANCELLED);
            assertThat(found.finishedAt()).isEqualTo(cancelledAt);
            assertThat(found.failureReason()).isNull();
        });
    }

    @Test
    void rejectsTheSecondSaveOfTwoCopiesOfTheSameRow() {
        PackingJob job = queuedJob(now());
        repository().save(job);

        PackingJob first = repository().findById(job.id())
                                       .orElseThrow();
        PackingJob second = repository().findById(job.id())
                                        .orElseThrow();
        repository().save(first);

        assertThatThrownBy(() -> repository().save(second))
                                                           .isInstanceOf(ConcurrentUpdateException.class)
                                                           .hasMessageContaining(job.id()
                                                                                    .toString());
    }

    @Test
    void persistsUppercaseChecksumsAsLowercase() {
        PackingJob job = queuedJob(now());
        job.succeed("output.bin",
                    "application/octet-stream",
                    12,
                    RESULT_CHECKSUM.toUpperCase(),
                    List.of(),
                    "packer 0.3.0",
                    ENGINE_CHECKSUM.toUpperCase(),
                    now());

        repository().save(job);

        assertThat(repository().findById(job.id())).hasValueSatisfying(found -> {
            assertThat(found.engineChecksum()).isEqualTo(ENGINE_CHECKSUM);
            assertThat(found.resultChecksum()).isEqualTo(RESULT_CHECKSUM);
        });
    }

    @Test
    void persistsAPendingPreviewAndThenItsModels() {
        PackingJob job = queuedJob(now());
        job.succeed("packed.obj", "model/obj", 12, RESULT_CHECKSUM, List.of(), "packer 0.3.0", ENGINE_CHECKSUM, now());
        repository().save(job);
        PackingJob pending = repository().findById(job.id())
                                         .orElseThrow();
        assertThat(pending.preview()).isEqualTo(PackingPreview.PENDING);

        List<PreviewModel> models = List.of(new PreviewModel(PreviewQuality.HIGH, 69_451, 1_234_567),
                                            new PreviewModel(PreviewQuality.LOW, 3_472, 45_678));
        pending.completePreview(models);
        repository().save(pending);

        assertThat(repository().findById(job.id())).hasValueSatisfying(found -> assertThat(found.preview()).isEqualTo(PackingPreview.ready(models)));
    }

    @Test
    void persistsAFailedPreview() {
        PackingJob job = queuedJob(now());
        job.succeed("packed.obj", "model/obj", 12, RESULT_CHECKSUM, List.of(), "packer 0.3.0", ENGINE_CHECKSUM, now());
        job.failPreview();

        repository().save(job);

        assertThat(repository().findById(job.id())).hasValueSatisfying(found -> assertThat(found.preview()).isEqualTo(PackingPreview.FAILED));
    }

    @Test
    void theDatabaseRejectsModelsOnAPreviewThatIsNotReady() {
        PackingJob job = queuedJob(now());
        repository().save(job);

        assertThatThrownBy(() -> dsl.update(PACKING_JOBS)
                                    .set(PACKING_JOBS.PREVIEW_MODELS, JSONB.valueOf("[]"))
                                    .where(PACKING_JOBS.ID.eq(job.id()))
                                    .execute()).hasStackTraceContaining("ck_packing_jobs_preview_models");
    }

    @Test
    void persistsTheResultBinsInOrder() {
        PackingJob job = queuedJob(now());
        List<ResultBin> bins = List.of(new ResultBin(7, "1".repeat(64)), new ResultBin(5, "2".repeat(64)));
        job.succeed("packed.obj", "model/obj", 12, RESULT_CHECKSUM, bins, "v0.0.5", ENGINE_CHECKSUM, now());

        repository().save(job);

        assertThat(repository().findById(job.id())).hasValueSatisfying(found -> assertThat(found.resultBins()).isEqualTo(bins));
    }

    @Test
    void theDatabaseRejectsResultBinsOnAJobThatHasNotSucceeded() {
        PackingJob job = queuedJob(now());
        repository().save(job);

        assertThatThrownBy(() -> dsl.update(PACKING_JOBS)
                                    .set(PACKING_JOBS.RESULT_BINS, JSONB.valueOf("[{\"sizeBytes\":1,\"checksum\":\"" + "a".repeat(64) + "\"}]"))
                                    .where(PACKING_JOBS.ID.eq(job.id()))
                                    .execute()).hasStackTraceContaining("ck_packing_jobs_result_bins");
    }

    @Test
    void roundTripsEveryItemAndTheParameters() {
        PackingInput input = new PackingInput(1,
                                              List.of(PackingInputs.item("bunny.obj", 3), PackingInputs.item("bracket.obj", 1)),
                                              new PackingParameters("{\"seed\":7}"));
        PackingJob job = PackingJob.queue(PackingJobId.generate(), project, user, input, 120, now());

        repository().save(job);

        assertThat(repository().findById(job.id())).hasValueSatisfying(found -> {
            assertThat(found.input()).isEqualTo(input);
            assertThat(found.maxRuntimeSeconds()).isEqualTo(120);
        });
    }

    @Test
    void readsALegacyRowButRefusesToSaveIt() {
        PackingJobId id = LegacyPackingJobs.insert(dsl, project, user, PackingJobStatus.SUCCEEDED, now());

        PackingJob legacy = repository().findById(id)
                                        .orElseThrow();

        assertThat(legacy.isLegacy()).isTrue();
        assertThatThrownBy(() -> repository().save(legacy)).isInstanceOf(IllegalStateException.class)
                                                           .hasMessageContaining("predates structured input");
    }

    private JooqPackingJobRepository repository() {
        return new JooqPackingJobRepository(dsl, new AggregateWriter(dsl));
    }

    private PackingJob queuedJob(Instant createdAt) {
        return PackingJob.queue(PackingJobId.generate(),
                                project,
                                user,
                                PackingInputs.single(),
                                60,
                                createdAt);
    }

    private static Instant now() {
        return Instant.now()
                      .truncatedTo(ChronoUnit.MICROS);
    }
}
