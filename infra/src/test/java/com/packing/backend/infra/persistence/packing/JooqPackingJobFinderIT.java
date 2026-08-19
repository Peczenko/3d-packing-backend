package com.packing.backend.infra.persistence.packing;

import com.packing.backend.core.packing.PackingJobView;
import com.packing.backend.core.packing.PackingJobListCriteria;
import com.packing.backend.core.shared.InstantRange;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JooqTest
@Import(TestcontainersConfiguration.class)
class JooqPackingJobFinderIT {

    @Autowired
    private DSLContext dsl;

    private UserId    user;
    private ProjectId projectA;
    private ProjectId projectB;

    @BeforeEach
    void createUserAndProjects() {
        Instant now = now();
        User owner = User.register(new FirebaseUid("packing-owner"),
                                   new Email("packing-owner@example.com"),
                                   new Username("packing-owner"),
                                   "Packing Owner",
                                   now);
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

        Page<PackingJobView> page = finder().listInProject(projectA, criteria(new PageRequest(0, 1)));

        assertThat(page.totalElements()).isEqualTo(2L);
        assertThat(page.content()).singleElement()
                                  .satisfies(view -> {
                                      assertThat(view.id()).isEqualTo(newest.id()
                                                                            .value());
                                      assertThat(view.projectId()).isEqualTo(projectA.value());
                                      assertThat(view.createdAt()).isEqualTo(base.plusSeconds(1));
                                  });
    }

    @Test
    void doesNotExposeAJobFromAnotherProject() {
        PackingJob jobInProjectB = persistJob(projectB, now());

        assertThat(finder().detailInProject(projectA, jobInProjectB.id())).isEmpty();
    }

    @Test
    void breaksCreatedAtTiesByDescendingJobId() {
        Instant createdAt = now();
        PackingJobId smallerId = new PackingJobId(
                                                  UUID.fromString("00000000-0000-0000-0000-000000000001"));
        PackingJobId largerId = new PackingJobId(
                                                 UUID.fromString("00000000-0000-0000-0000-000000000002"));
        persistJob(projectA, smallerId, createdAt);
        persistJob(projectA, largerId, createdAt);

        assertThat(finder().listInProject(projectA, criteria(new PageRequest(0, 1)))
                           .content())
                                      .extracting(PackingJobView::id)
                                      .containsExactly(largerId.value());
    }

    @Test
    void findsOnlyRunningJobsInStartedAtThenIdOrderAndHonorsTheLimit() {
        Instant base = now();
        PackingJobId firstId = new PackingJobId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        PackingJobId secondId = new PackingJobId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        PackingJob first = runningJob(projectA, firstId, base, base.plusSeconds(10));
        PackingJob second = runningJob(projectA, secondId, base.plusSeconds(1), base.plusSeconds(10));
        persist(first);
        persist(second);
        persistJob(projectA, base.plusSeconds(2));

        assertThat(finder().findRunning(1)).containsExactly(first.id());
        assertThat(finder().findRunning(2)).containsExactly(first.id(), second.id());
    }

    @Test
    void rejectsANonPositiveRunningJobLimit() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                                       .isThrownBy(() -> finder().findRunning(0))
                                       .withMessage("limit must be positive");
    }

    @Test
    void matchesSearchInEngineVersionResultFileNameAndFailureReason() {
        Instant base = now();
        PackingJob engine = persistJob(projectA, PackingJobStatus.RUNNING, 60, "Engine 2.0", base, base, null, null, null, null);
        PackingJob file = persistJob(projectA, PackingJobStatus.SUCCEEDED, 60, "other", base.plusSeconds(1), base, base, "engine-result.glb", 10L, null);
        PackingJob failure = persistJob(projectA, PackingJobStatus.FAILED, 60, "other", base.plusSeconds(2), base, base, null, null, "engine exploded");
        persistJob(projectA, PackingJobStatus.QUEUED, 60, null, base.plusSeconds(3), null, null, null, null, null);

        Page<PackingJobView> page = finder().listInProject(projectA,
                                                           criteria(new PageRequest(0, 10),
                                                                    "ENGINE",
                                                                    Set.of(),
                                                                    new InstantRange(null, null),
                                                                    new InstantRange(null, null),
                                                                    new InstantRange(null, null),
                                                                    PackingJobListCriteria.SortField.CREATED_AT,
                                                                    SortDirection.ASC));

        assertThat(page.content()).extracting(PackingJobView::id)
                                  .containsExactly(engine.id()
                                                         .value(),
                                                   file.id()
                                                       .value(),
                                                   failure.id()
                                                          .value());
        assertThat(page.totalElements()).isEqualTo(3L);
    }

    @Test
    void treatsPercentAndUnderscoreAsLiteralCaseInsensitiveSearchCharacters() {
        Instant base = now();
        PackingJob exact = persistJob(projectA, PackingJobStatus.RUNNING, 60, "Packer 100%_ready", base, base, null, null, null, null);
        persistJob(projectA, PackingJobStatus.RUNNING, 60, "Packer 100XXready", base.plusSeconds(1), base, null, null, null, null);
        persistJob(projectA, PackingJobStatus.RUNNING, 60, "Packer 100%Xready", base.plusSeconds(2), base, null, null, null, null);

        assertThat(finder().listInProject(projectA,
                                          criteria(new PageRequest(0, 10),
                                                   "100%_READY",
                                                   Set.of(),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   PackingJobListCriteria.SortField.CREATED_AT,
                                                   SortDirection.ASC))
                           .content())
                                      .extracting(PackingJobView::id)
                                      .containsExactly(exact.id()
                                                            .value());
    }

    @Test
    void filtersRepeatedStatusesAndAllTimestampRangesWithHalfOpenBounds() {
        Instant base = now();
        PackingJob matching = persistJob(projectA, PackingJobStatus.RUNNING, 60, "engine", base, base.plusSeconds(10), base.plusSeconds(20), null, null, null);
        persistJob(projectA, PackingJobStatus.FAILED, 60, "engine", base.plusSeconds(1), base.plusSeconds(11), base.plusSeconds(22), null, null, "failed");
        persistJob(projectA, PackingJobStatus.RUNNING, 60, "engine", base.plusSeconds(2), base.plusSeconds(12), base.plusSeconds(22), null, null, null);
        persistJob(projectA, PackingJobStatus.SUCCEEDED, 60, "engine", base, base.plusSeconds(10), base.plusSeconds(20), "result.glb", 1L, null);

        PackingJobListCriteria criteria = criteria(new PageRequest(0, 10),
                                                   null,
                                                   Set.of(PackingJobStatus.RUNNING, PackingJobStatus.FAILED),
                                                   new InstantRange(base, base.plusSeconds(2)),
                                                   new InstantRange(base.plusSeconds(10), base.plusSeconds(12)),
                                                   new InstantRange(base.plusSeconds(20), base.plusSeconds(22)),
                                                   PackingJobListCriteria.SortField.CREATED_AT,
                                                   SortDirection.ASC);

        assertThat(finder().listInProject(projectA, criteria)
                           .content())
                                      .extracting(PackingJobView::id)
                                      .containsExactly(matching.id()
                                                               .value());
    }

    @Test
    void leavesNullStartedAndFinishedTimesEligibleWithoutBoundsAndExcludesThemWithBounds() {
        Instant base = now();
        PackingJob queued = persistJob(projectA, PackingJobStatus.QUEUED, 60, null, base, null, null, null, null, null);
        PackingJob running = persistJob(projectA, PackingJobStatus.RUNNING, 60, "engine", base.plusSeconds(1), base.plusSeconds(1), null, null, null, null);
        PackingJob finished = persistJob(projectA, PackingJobStatus.SUCCEEDED, 60, "engine", base.plusSeconds(2), base.plusSeconds(2), base.plusSeconds(2), "result.glb", 1L, null);

        assertThat(finder().listInProject(projectA, criteria(new PageRequest(0, 10)))
                           .content())
                                      .extracting(PackingJobView::id)
                                      .containsExactly(finished.id()
                                                               .value(),
                                                       running.id()
                                                              .value(),
                                                       queued.id()
                                                             .value());
        assertThat(finder().listInProject(projectA,
                                          criteria(new PageRequest(0, 10),
                                                   null,
                                                   Set.of(),
                                                   new InstantRange(null, null),
                                                   new InstantRange(base, null),
                                                   new InstantRange(null, null),
                                                   PackingJobListCriteria.SortField.CREATED_AT,
                                                   SortDirection.ASC))
                           .content())
                                      .extracting(PackingJobView::id)
                                      .containsExactly(running.id()
                                                              .value(),
                                                       finished.id()
                                                               .value());
        assertThat(finder().listInProject(projectA,
                                          criteria(new PageRequest(0, 10),
                                                   null,
                                                   Set.of(),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   new InstantRange(base, null),
                                                   PackingJobListCriteria.SortField.CREATED_AT,
                                                   SortDirection.ASC))
                           .content())
                                      .extracting(PackingJobView::id)
                                      .containsExactly(finished.id()
                                                               .value());
    }

    @Test
    void sortsByEverySupportedFieldInBothDirections() {
        Instant base = now();
        PackingJob first = persistJob(projectA, PackingJobStatus.QUEUED, 10, "alpha", base, base, base, "alpha.glb", 100L, null);
        PackingJob second = persistJob(projectA, PackingJobStatus.RUNNING, 20, "Bravo", base.plusSeconds(1), base.plusSeconds(1), base.plusSeconds(1), "Bravo.glb", 200L, null);
        PackingJob third = persistJob(projectA,
                                      PackingJobStatus.SUCCEEDED,
                                      30,
                                      "charlie",
                                      base.plusSeconds(2),
                                      base.plusSeconds(2),
                                      base.plusSeconds(2),
                                      "charlie.glb",
                                      300L,
                                      null);
        List<UUID> ascending = List.of(first.id()
                                            .value(),
                                       second.id()
                                             .value(),
                                       third.id()
                                            .value());

        for (PackingJobListCriteria.SortField sort : PackingJobListCriteria.SortField.values()) {
            assertSorted(sort, ascending);
        }
    }

    @Test
    void appliesExplicitStatusRankCaseInsensitiveTextOrderNullsLastAndIdTieBreaking() {
        Instant base = now();
        PackingJob queued = persistJob(projectA, PackingJobStatus.QUEUED, 60, "bravo", base, null, null, null, null, null);
        PackingJob running = persistJob(projectA, PackingJobStatus.RUNNING, 60, "Alpha", base.plusSeconds(1), base, null, null, null, null);
        PackingJob succeeded = persistJob(projectA, PackingJobStatus.SUCCEEDED, 60, null, base.plusSeconds(2), base, base, "charlie.glb", 1L, null);
        PackingJob failed = persistJob(projectA, PackingJobStatus.FAILED, 60, null, base.plusSeconds(3), base, base, null, null, "failed");

        assertThat(finder().listInProject(projectA,
                                          criteria(new PageRequest(0, 10),
                                                   null,
                                                   Set.of(),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   PackingJobListCriteria.SortField.STATUS,
                                                   SortDirection.ASC))
                           .content())
                                      .extracting(PackingJobView::id)
                                      .containsExactly(queued.id()
                                                             .value(),
                                                       running.id()
                                                              .value(),
                                                       succeeded.id()
                                                                .value(),
                                                       failed.id()
                                                             .value());
        assertThat(finder().listInProject(projectA,
                                          criteria(new PageRequest(0, 10),
                                                   null,
                                                   Set.of(),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   PackingJobListCriteria.SortField.ENGINE_VERSION,
                                                   SortDirection.ASC))
                           .content())
                                      .extracting(PackingJobView::id)
                                      .containsExactly(running.id()
                                                              .value(),
                                                       queued.id()
                                                             .value(),
                                                       List.of(succeeded.id()
                                                                        .value(),
                                                               failed.id()
                                                                     .value())
                                                           .stream()
                                                           .sorted(Comparator.comparing(UUID::toString))
                                                           .findFirst()
                                                           .orElseThrow(),
                                                       List.of(succeeded.id()
                                                                        .value(),
                                                               failed.id()
                                                                     .value())
                                                           .stream()
                                                           .sorted(Comparator.comparing(UUID::toString))
                                                           .skip(1)
                                                           .findFirst()
                                                           .orElseThrow());
    }

    @Test
    void sortsEveryNullableFieldWithNullsLastInBothDirections() {
        Instant base = now();
        for (PackingJobListCriteria.SortField sort : List.of(PackingJobListCriteria.SortField.ENGINE_VERSION,
                                                             PackingJobListCriteria.SortField.STARTED_AT,
                                                             PackingJobListCriteria.SortField.FINISHED_AT,
                                                             PackingJobListCriteria.SortField.RESULT_FILE_NAME,
                                                             PackingJobListCriteria.SortField.RESULT_SIZE_BYTES)) {
            ProjectId project = persistProject(sort.name(), base);
            PackingJob populated = persistJob(project,
                                              PackingJobStatus.SUCCEEDED,
                                              60,
                                              "engine",
                                              base,
                                              base,
                                              base,
                                              "result.glb",
                                              1L,
                                              null);
            PackingJob empty = switch (sort) {
                case ENGINE_VERSION -> persistJob(project,
                                                  PackingJobStatus.SUCCEEDED,
                                                  60,
                                                  null,
                                                  base.plusSeconds(1),
                                                  base,
                                                  base,
                                                  "result.glb",
                                                  1L,
                                                  null);
                case STARTED_AT -> persistJob(project,
                                              PackingJobStatus.SUCCEEDED,
                                              60,
                                              "engine",
                                              base.plusSeconds(1),
                                              null,
                                              base,
                                              "result.glb",
                                              1L,
                                              null);
                case FINISHED_AT -> persistJob(project,
                                               PackingJobStatus.RUNNING,
                                               60,
                                               "engine",
                                               base.plusSeconds(1),
                                               base,
                                               null,
                                               "result.glb",
                                               1L,
                                               null);
                case RESULT_FILE_NAME -> persistJob(project,
                                                    PackingJobStatus.FAILED,
                                                    60,
                                                    "engine",
                                                    base.plusSeconds(1),
                                                    base,
                                                    base,
                                                    null,
                                                    null,
                                                    "failed");
                case RESULT_SIZE_BYTES -> persistJob(project,
                                                     PackingJobStatus.SUCCEEDED,
                                                     60,
                                                     "engine",
                                                     base.plusSeconds(1),
                                                     base,
                                                     base,
                                                     "result.glb",
                                                     null,
                                                     null);
                default -> throw new IllegalStateException("Unexpected nullable sort: " + sort);
            };

            for (SortDirection direction : SortDirection.values()) {
                assertThat(finder().listInProject(project,
                                                  criteria(new PageRequest(0, 10),
                                                           null,
                                                           Set.of(),
                                                           new InstantRange(null, null),
                                                           new InstantRange(null, null),
                                                           new InstantRange(null, null),
                                                           sort,
                                                           direction))
                                   .content())
                                              .extracting(PackingJobView::id)
                                              .containsExactly(populated.id()
                                                                        .value(),
                                                               empty.id()
                                                                    .value());
            }
        }
    }

    @Test
    void breaksCaseInsensitiveTextTiesByJobIdInTheRequestedDirection() {
        Instant createdAt = now();
        PackingJobId smaller = new PackingJobId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        PackingJobId larger = new PackingJobId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        persistJob(projectA, smaller, PackingJobStatus.RUNNING, 60, "engine", createdAt, createdAt, null, null, null, null);
        persistJob(projectA, larger, PackingJobStatus.RUNNING, 60, "Engine", createdAt, createdAt, null, null, null, null);

        assertThat(finder().listInProject(projectA,
                                          criteria(new PageRequest(0, 10),
                                                   null,
                                                   Set.of(),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   PackingJobListCriteria.SortField.ENGINE_VERSION,
                                                   SortDirection.ASC))
                           .content())
                                      .extracting(PackingJobView::id)
                                      .containsExactly(smaller.value(), larger.value());
        assertThat(finder().listInProject(projectA,
                                          criteria(new PageRequest(0, 10),
                                                   null,
                                                   Set.of(),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   PackingJobListCriteria.SortField.ENGINE_VERSION,
                                                   SortDirection.DESC))
                           .content())
                                      .extracting(PackingJobView::id)
                                      .containsExactly(larger.value(), smaller.value());
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
        return persistJob(project, PackingJobId.generate(), createdAt);
    }

    private PackingJob persistJob(ProjectId project, PackingJobId id, Instant createdAt) {
        PackingJob job = PackingJob.queue(id,
                                          project,
                                          user,
                                          "{\"testField\":42}",
                                          60,
                                          createdAt);
        persist(job);
        return job;
    }

    private PackingJob persistJob(ProjectId project,
                                  PackingJobStatus status,
                                  long runtime,
                                  String engineVersion,
                                  Instant createdAt,
                                  Instant startedAt,
                                  Instant finishedAt,
                                  String resultFileName,
                                  Long resultSizeBytes,
                                  String failureReason) {
        return persistJob(project,
                          PackingJobId.generate(),
                          status,
                          runtime,
                          engineVersion,
                          createdAt,
                          startedAt,
                          finishedAt,
                          resultFileName,
                          resultSizeBytes,
                          failureReason);
    }

    private PackingJob persistJob(ProjectId project,
                                  PackingJobId id,
                                  PackingJobStatus status,
                                  long runtime,
                                  String engineVersion,
                                  Instant createdAt,
                                  Instant startedAt,
                                  Instant finishedAt,
                                  String resultFileName,
                                  Long resultSizeBytes,
                                  String failureReason) {
        PackingJob job = PackingJob.rehydrate(id,
                                              project,
                                              user,
                                              "{\"testField\":42}",
                                              runtime,
                                              status,
                                              engineVersion,
                                              engineVersion == null ? null : "a".repeat(64),
                                              null,
                                              startedAt,
                                              finishedAt,
                                              resultFileName,
                                              resultFileName == null ? null : "application/octet-stream",
                                              resultFileName == null ? null : "b".repeat(64),
                                              resultSizeBytes,
                                              failureReason,
                                              0,
                                              createdAt);
        persist(job);
        return job;
    }

    private PackingJob runningJob(ProjectId project, PackingJobId id, Instant createdAt, Instant startedAt) {
        PackingJob job = PackingJob.queue(id, project, user, "{\"testField\":42}", 60, createdAt);
        job.markRunning("packer 0.1.0", "a".repeat(64), startedAt);
        return job;
    }

    private void persist(PackingJob job) {
        new JooqPackingJobRepository(dsl, new AggregateWriter(dsl)).save(job);
    }

    private static PackingJobListCriteria criteria(PageRequest page) {
        return criteria(page,
                        null,
                        Set.of(),
                        new InstantRange(null, null),
                        new InstantRange(null, null),
                        new InstantRange(null, null),
                        PackingJobListCriteria.SortField.CREATED_AT,
                        SortDirection.DESC);
    }

    private static PackingJobListCriteria criteria(PageRequest page,
                                                   String search,
                                                   Set<PackingJobStatus> statuses,
                                                   InstantRange createdAt,
                                                   InstantRange startedAt,
                                                   InstantRange finishedAt,
                                                   PackingJobListCriteria.SortField sort,
                                                   SortDirection direction) {
        return new PackingJobListCriteria(page, search, statuses, createdAt, startedAt, finishedAt, sort, direction);
    }

    private void assertSorted(PackingJobListCriteria.SortField sort, List<UUID> ascending) {
        assertThat(finder().listInProject(projectA,
                                          criteria(new PageRequest(0, 10),
                                                   null,
                                                   Set.of(),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   sort,
                                                   SortDirection.ASC))
                           .content())
                                      .extracting(PackingJobView::id)
                                      .containsExactlyElementsOf(ascending);
        assertThat(finder().listInProject(projectA,
                                          criteria(new PageRequest(0, 10),
                                                   null,
                                                   Set.of(),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   new InstantRange(null, null),
                                                   sort,
                                                   SortDirection.DESC))
                           .content())
                                      .extracting(PackingJobView::id)
                                      .containsExactlyElementsOf(ascending.reversed());
    }

    private static Instant now() {
        return Instant.now()
                      .truncatedTo(ChronoUnit.MICROS);
    }
}
