package com.packing.backend.infra.persistence.packing;

import com.packing.backend.core.packing.PackingJobView;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@JooqTest
@Import(TestcontainersConfiguration.class)
class JooqPackingJobFinderIT {

    @Autowired
    private DSLContext dsl;

    private UserId user;
    private ProjectId projectA;
    private ProjectId projectB;

    @BeforeEach
    void createUserAndProjects() {
        Instant now = now();
        User owner = User.register(new FirebaseUid("packing-owner"),
                new Email("packing-owner@example.com"), new Username("packing-owner"),
                "Packing Owner", now);
        new JooqUserRepository(dsl, new AggregateWriter(dsl)).save(owner);
        user = owner.id();
        projectA = persistProject("Project A", now);
        projectB = persistProject("Project B", now);
    }

    @Test
    void listsOnlyTheNewestJobFromTheRequestedProjectAndCountsAllItsJobs() {
        Instant base = now();
        persistJob(projectA, base);
        PackingJob newest = persistJob(projectA, base.plusSeconds(1));
        persistJob(projectB, base.plusSeconds(2));

        Page<PackingJobView> page = finder().listInProject(projectA, new PageRequest(0, 1));

        assertThat(page.totalElements()).isEqualTo(2L);
        assertThat(page.content()).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(newest.id().value());
            assertThat(view.projectId()).isEqualTo(projectA.value());
            assertThat(view.createdAt()).isEqualTo(base.plusSeconds(1));
        });
    }

    @Test
    void doesNotExposeAJobFromAnotherProject() {
        PackingJob jobInProjectB = persistJob(projectB, now());

        assertThat(finder().detailInProject(projectA, jobInProjectB.id())).isEmpty();
    }

    private JooqPackingJobFinder finder() {
        return new JooqPackingJobFinder(dsl);
    }

    private ProjectId persistProject(String name, Instant createdAt) {
        Project project = Project.create(new ProjectName(name), user, createdAt);
        new JooqProjectRepository(dsl, new AggregateWriter(dsl)).save(project);
        return project.id();
    }

    private PackingJob persistJob(ProjectId project, Instant createdAt) {
        PackingJob job = PackingJob.queue(PackingJobId.generate(), project, user,
                "{\"testField\":42}", 60, createdAt);
        new JooqPackingJobRepository(dsl, new AggregateWriter(dsl)).save(job);
        return job;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
