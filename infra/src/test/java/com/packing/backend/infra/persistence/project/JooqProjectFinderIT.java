package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.project.ProjectSummaryView;
import com.packing.backend.core.project.ProjectListCriteria;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.InstantRange;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;
import com.packing.backend.infra.TestcontainersConfiguration;
import com.packing.backend.infra.persistence.shared.AggregateWriter;
import com.packing.backend.infra.persistence.user.JooqUserRepository;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListenerProvider;
import org.jooq.impl.DefaultExecuteListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@JooqTest
@Import(TestcontainersConfiguration.class)
class JooqProjectFinderIT {

    @Autowired
    private DSLContext dsl;

    private UserId creator;
    private UserId member;
    private UserId outsider;

    private JooqProjectFinder finder() {
        return new JooqProjectFinder(dsl);
    }

    private JooqProjectRepository repository() {
        return new JooqProjectRepository(dsl, new AggregateWriter(dsl));
    }

    private static Instant now() {
        return Instant.now()
                      .truncatedTo(ChronoUnit.MICROS);
    }

    @BeforeEach
    void createUsers() {
        creator = persistUser("uid-creator", "creator");
        member = persistUser("uid-member", "member");
        outsider = persistUser("uid-outsider", "outsider");
    }

    private UserId persistUser(String uid, String username) {
        User user = User.register(new FirebaseUid(uid),
                                  new Email(username + "@example.com"),
                                  new Username(username),
                                  "Display of " + username,
                                  now());
        new JooqUserRepository(dsl, new AggregateWriter(dsl)).save(user);
        return user.id();
    }

    private Project persistedProject() {
        Project project = Project.create(new ProjectName("Chassis packing"), creator, now());
        repository().save(project);
        return project;
    }

    private static ProjectListCriteria criteria(PageRequest page) {
        return criteria(page,
                        null,
                        Set.of(),
                        Set.of(),
                        new InstantRange(null, null),
                        new InstantRange(null, null),
                        ProjectListCriteria.SortField.CREATED_AT,
                        SortDirection.DESC);
    }

    private static ProjectListCriteria criteria(PageRequest page,
                                                String search,
                                                Set<ProjectStatus> statuses,
                                                Set<ProjectPermission> permissions,
                                                InstantRange createdAt,
                                                InstantRange updatedAt,
                                                ProjectListCriteria.SortField sort,
                                                SortDirection direction) {
        return new ProjectListCriteria(page,
                                       search,
                                       statuses,
                                       permissions,
                                       createdAt,
                                       updatedAt,
                                       sort,
                                       direction);
    }

    @Test
    void listsOnlyProjectsTheCallerBelongsTo() {
        Project mine = persistedProject();
        repository().save(Project.create(new ProjectName("Theirs"), member, now()));

        Page<ProjectSummaryView> page = finder().listForMember(creator, criteria(new PageRequest(0, 10)));

        assertThat(page.content()).extracting(ProjectSummaryView::id)
                                  .containsExactly(mine.id()
                                                       .value());
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    void listExcludesDeletedButKeepsDisabledProjectsNewestFirst() {
        Instant base = now();
        repository().save(Project.create(new ProjectName("Chassis packing"), creator, base));
        repository().save(Project.create(new ProjectName("Newer"), creator, base.plusSeconds(1)));

        Project disabled = Project.create(new ProjectName("Disabled"), creator, base.plusSeconds(2));
        repository().save(disabled);
        disabled.disable(base.plusSeconds(3));
        repository().save(disabled);

        Project removed = Project.create(new ProjectName("Removed"), creator, base.plusSeconds(4));
        repository().save(removed);
        removed.delete(base.plusSeconds(5));
        repository().save(removed);

        Page<ProjectSummaryView> page = finder().listForMember(creator, criteria(new PageRequest(0, 10)));

        assertThat(page.content()).extracting(ProjectSummaryView::name)
                                  .containsExactly("Disabled", "Newer", "Chassis packing");
        assertThat(page.totalElements()).isEqualTo(3L);
    }

    @Test
    void listPagesAndReportsTheTotalIndependentlyOfThePageSize() {
        for (int i = 0; i < 5; i++) {
            repository().save(Project.create(new ProjectName("P" + i),
                                             creator,
                                             now().plusSeconds(i)));
        }

        assertThat(finder().listForMember(creator, criteria(new PageRequest(0, 2)))
                           .content()).hasSize(2);
        assertThat(finder().listForMember(creator, criteria(new PageRequest(2, 2)))
                           .content()).hasSize(1);
        assertThat(finder().listForMember(creator, criteria(new PageRequest(5, 2)))
                           .content()).isEmpty();
        assertThat(finder().listForMember(creator, criteria(new PageRequest(5, 2)))
                           .totalElements())
                                            .isEqualTo(5L);
    }

    @Test
    void summaryCarriesTheMemberCountAndTheCallersOwnPermission() {
        Project project = persistedProject();
        project.grantAccess(member, ProjectPermission.WRITE, creator, now());
        repository().save(project);

        assertThat(finder().listForMember(member, criteria(new PageRequest(0, 10)))
                           .content())
                                      .singleElement()
                                      .satisfies(summary -> {
                                          assertThat(summary.memberCount()).isEqualTo(2);
                                          assertThat(summary.myPermission()).isEqualTo(ProjectPermission.WRITE);
                                          assertThat(summary.status()).isEqualTo(ProjectStatus.ACTIVE);
                                      });
    }

    @Test
    void theMemberCountIsCorrelatedToItsOwnProject() {
        Project shared = persistedProject();
        shared.grantAccess(member, ProjectPermission.WRITE, creator, now());
        repository().save(shared);
        repository().save(Project.create(new ProjectName("Solo"), creator, now().plusSeconds(1)));

        Page<ProjectSummaryView> page = finder().listForMember(creator, criteria(new PageRequest(0, 10)));

        assertThat(page.content())
                                  .extracting(ProjectSummaryView::name, ProjectSummaryView::memberCount)
                                  .containsExactly(tuple("Solo", 1), tuple("Chassis packing", 2));
    }

    @Test
    void aPageOfSummariesIsTwoStatementsRegardlessOfRosterSize() {
        Project project = persistedProject();
        project.grantAccess(member, ProjectPermission.WRITE, creator, now());
        repository().save(project);

        AtomicInteger statements = new AtomicInteger();
        DSLContext counting = dsl.configuration()
                                 .derive((ExecuteListenerProvider) () -> new DefaultExecuteListener() {

                                     @Override
                                     public void executeStart(ExecuteContext ctx) {
                                         statements.incrementAndGet();
                                     }
                                 })
                                 .dsl();

        new JooqProjectFinder(counting).listForMember(creator, criteria(new PageRequest(0, 10)));

        assertThat(statements).hasValue(2);
    }

    @Test
    void listFiltersCaseInsensitiveLiteralNamesEnumsAndTimestampRanges() {
        Instant base = now();
        Project match = Project.create(new ProjectName("100% PACKING_box"), creator, base);
        match.grantAccess(member, ProjectPermission.WRITE, creator, base.plusSeconds(1));
        match.disable(base.plusSeconds(2));
        repository().save(match);

        Project wrongPermission = Project.create(new ProjectName("100% packing_box other"), creator, base);
        wrongPermission.grantAccess(member, ProjectPermission.READ, creator, base.plusSeconds(1));
        wrongPermission.disable(base.plusSeconds(2));
        repository().save(wrongPermission);

        Project wrongStatus = Project.create(new ProjectName("100% packing_box active"), creator, base);
        wrongStatus.grantAccess(member, ProjectPermission.WRITE, creator, base.plusSeconds(1));
        repository().save(wrongStatus);

        Project noPercent = Project.create(new ProjectName("plain packing_box"), creator, base);
        noPercent.grantAccess(member, ProjectPermission.WRITE, creator, base.plusSeconds(1));
        repository().save(noPercent);

        ProjectListCriteria filtered = criteria(
                                                 new PageRequest(0, 10),
                                                 "100% packing_",
                                                 Set.of(ProjectStatus.DISABLED),
                                                 Set.of(ProjectPermission.WRITE, ProjectPermission.OWNER),
                                                 new InstantRange(base, base.plusSeconds(1)),
                                                 new InstantRange(base.plusSeconds(2), base.plusSeconds(3)),
                                                 ProjectListCriteria.SortField.NAME,
                                                 SortDirection.ASC);

        Page<ProjectSummaryView> page = finder().listForMember(member, filtered);

        assertThat(page.content()).extracting(ProjectSummaryView::id)
                                  .containsExactly(match.id().value());
        assertThat(page.totalElements()).isEqualTo(1L);
        assertThat(finder().listForMember(member, criteria(new PageRequest(0, 10),
                                                            "%",
                                                            Set.of(),
                                                            Set.of(),
                                                            new InstantRange(null, null),
                                                            new InstantRange(null, null),
                                                            ProjectListCriteria.SortField.NAME,
                                                            SortDirection.ASC))
                           .content()).extracting(ProjectSummaryView::id)
                                      .containsExactlyInAnyOrder(match.id().value(),
                                                                 wrongPermission.id().value(),
                                                                 wrongStatus.id().value());
        assertThat(finder().listForMember(member, criteria(new PageRequest(0, 10),
                                                            "100% packing_",
                                                            Set.of(ProjectStatus.ACTIVE, ProjectStatus.DISABLED),
                                                            Set.of(ProjectPermission.WRITE, ProjectPermission.OWNER),
                                                            new InstantRange(base, base.plusSeconds(1)),
                                                            new InstantRange(null, null),
                                                            ProjectListCriteria.SortField.NAME,
                                                            SortDirection.ASC))
                           .content()).extracting(ProjectSummaryView::id)
                                      .containsExactlyInAnyOrder(match.id().value(), wrongStatus.id().value());
        assertThat(finder().listForMember(member, criteria(new PageRequest(1, 1),
                                                            "100% packing_",
                                                            Set.of(ProjectStatus.DISABLED),
                                                            Set.of(ProjectPermission.WRITE),
                                                            new InstantRange(base, base.plusSeconds(1)),
                                                            new InstantRange(base.plusSeconds(2), base.plusSeconds(3)),
                                                            ProjectListCriteria.SortField.NAME,
                                                            SortDirection.ASC))
                           .content()).isEmpty();
    }

    @Test
    void listSortsEveryFieldInBothDirectionsWithBusinessRanksAndIdTieBreaking() {
        Instant base = now();
        Project alpha = Project.create(new ProjectName("alpha"), creator, base);
        alpha.grantAccess(member, ProjectPermission.READ, creator, base.plusSeconds(1));
        alpha.disable(base.plusSeconds(2));
        repository().save(alpha);

        Project bravo = Project.create(new ProjectName("Bravo"), creator, base);
        bravo.grantAccess(member, ProjectPermission.WRITE, creator, base.plusSeconds(3));
        bravo.grantAccess(outsider, ProjectPermission.READ, creator, base.plusSeconds(4));
        repository().save(bravo);

        Project charlie = Project.create(new ProjectName("charlie"), creator, base);
        charlie.grantAccess(member, ProjectPermission.OWNER, creator, base.plusSeconds(5));
        charlie.grantAccess(outsider, ProjectPermission.READ, creator, base.plusSeconds(6));
        repository().save(charlie);

        for (ProjectListCriteria.SortField sort : ProjectListCriteria.SortField.values()) {
            Page<ProjectSummaryView> ascending = finder().listForMember(member, criteria(
                                                                                            new PageRequest(0, 10),
                                                                                            null,
                                                                                            Set.of(),
                                                                                            Set.of(),
                                                                                            new InstantRange(null, null),
                                                                                            new InstantRange(null, null),
                                                                                            sort,
                                                                                            SortDirection.ASC));
            Page<ProjectSummaryView> descending = finder().listForMember(member, criteria(
                                                                                             new PageRequest(0, 10),
                                                                                             null,
                                                                                             Set.of(),
                                                                                             Set.of(),
                                                                                             new InstantRange(null, null),
                                                                                             new InstantRange(null, null),
                                                                                             sort,
                                                                                             SortDirection.DESC));

            assertThat(descending.content()).extracting(ProjectSummaryView::id)
                                            .containsExactlyElementsOf(ascending.content()
                                                                                .stream()
                                                                                .map(ProjectSummaryView::id)
                                                                                .toList()
                                                                                .reversed());
        }

        assertThat(finder().listForMember(member, criteria(new PageRequest(0, 10),
                                                            null,
                                                            Set.of(),
                                                            Set.of(),
                                                            new InstantRange(null, null),
                                                            new InstantRange(null, null),
                                                            ProjectListCriteria.SortField.NAME,
                                                            SortDirection.ASC))
                           .content())
                                         .extracting(ProjectSummaryView::name)
                                         .containsExactly("alpha", "Bravo", "charlie");
        assertThat(finder().listForMember(member, criteria(new PageRequest(0, 10),
                                                            null,
                                                            Set.of(),
                                                            Set.of(),
                                                            new InstantRange(null, null),
                                                            new InstantRange(null, null),
                                                            ProjectListCriteria.SortField.STATUS,
                                                            SortDirection.ASC))
                           .content())
                                         .extracting(ProjectSummaryView::status)
                                         .containsExactly(ProjectStatus.ACTIVE, ProjectStatus.ACTIVE, ProjectStatus.DISABLED);
        assertThat(finder().listForMember(member, criteria(new PageRequest(0, 10),
                                                            null,
                                                            Set.of(),
                                                            Set.of(),
                                                            new InstantRange(null, null),
                                                            new InstantRange(null, null),
                                                            ProjectListCriteria.SortField.PERMISSION,
                                                            SortDirection.ASC))
                           .content())
                                         .extracting(ProjectSummaryView::myPermission)
                                         .containsExactly(ProjectPermission.READ, ProjectPermission.WRITE, ProjectPermission.OWNER);
    }

    @Test
    void detailCarriesEveryMemberWithTheirIdentity() {
        Project project = persistedProject();
        project.grantAccess(member, ProjectPermission.WRITE, creator, now());
        repository().save(project);

        assertThat(finder().detailFor(creator, project.id())).hasValueSatisfying(view -> {
            assertThat(view.id()).isEqualTo(project.id()
                                                   .value());
            assertThat(view.createdBy()).isEqualTo(creator.value());
            assertThat(view.myPermission()).isEqualTo(ProjectPermission.OWNER);
            assertThat(view.members()).hasSize(2);
            assertThat(view.members())
                                      .extracting(m -> m.username(), m -> m.displayName())
                                      .containsExactly(
                                                       tuple("creator", "Display of creator"),
                                                       tuple("member", "Display of member"));
        });
    }

    @Test
    void detailIsOneStatementIncludingTheRoster() {
        Project project = persistedProject();

        AtomicInteger statements = new AtomicInteger();
        DSLContext counting = dsl.configuration()
                                 .derive((ExecuteListenerProvider) () -> new DefaultExecuteListener() {

                                     @Override
                                     public void executeStart(ExecuteContext ctx) {
                                         statements.incrementAndGet();
                                     }
                                 })
                                 .dsl();

        assertThat(new JooqProjectFinder(counting).detailFor(creator, project.id())).isPresent();
        assertThat(statements).hasValue(1);
    }

    @Test
    void detailIsEmptyForANonMember() {
        Project project = persistedProject();

        assertThat(finder().detailFor(outsider, project.id())).isEmpty();
    }

    @Test
    void detailIsEmptyForATombstoneAndForAnUnknownId() {
        Project project = persistedProject();
        project.delete(now());
        repository().save(project);

        assertThat(finder().detailFor(creator, project.id())).isEmpty();
        assertThat(finder().detailFor(creator, ProjectId.generate())).isEmpty();
    }

    @Test
    void detailStillResolvesADisabledProject() {
        Project project = persistedProject();
        project.disable(now());
        repository().save(project);

        assertThat(finder().detailFor(creator, project.id()))
                                                             .hasValueSatisfying(view -> assertThat(view.status()).isEqualTo(ProjectStatus.DISABLED));
    }
}
