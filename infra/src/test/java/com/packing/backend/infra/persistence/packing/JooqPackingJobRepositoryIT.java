package com.packing.backend.infra.persistence.packing;

import com.packing.backend.core.shared.ConcurrentUpdateException;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
        JSONAssert.assertEquals("{\"testField\":42}", reloaded.specJson(), JSONCompareMode.STRICT);
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
                    "packer 0.3.0",
                    ENGINE_CHECKSUM.toUpperCase(),
                    now());

        repository().save(job);

        assertThat(repository().findById(job.id())).hasValueSatisfying(found -> {
            assertThat(found.engineChecksum()).isEqualTo(ENGINE_CHECKSUM);
            assertThat(found.resultChecksum()).isEqualTo(RESULT_CHECKSUM);
        });
    }

    private JooqPackingJobRepository repository() {
        return new JooqPackingJobRepository(dsl, new AggregateWriter(dsl));
    }

    private PackingJob queuedJob(Instant createdAt) {
        return PackingJob.queue(PackingJobId.generate(),
                                project,
                                user,
                                "{\"testField\":42}",
                                60,
                                createdAt);
    }

    private static Instant now() {
        return Instant.now()
                      .truncatedTo(ChronoUnit.MICROS);
    }
}
