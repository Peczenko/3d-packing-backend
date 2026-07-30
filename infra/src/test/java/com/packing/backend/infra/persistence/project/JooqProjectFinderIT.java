package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.project.ProjectSummaryView;
import com.packing.backend.core.project.ProjectView;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
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
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @BeforeEach
    void createUsers() {
        creator = persistUser("uid-creator", "creator");
        member = persistUser("uid-member", "member");
        outsider = persistUser("uid-outsider", "outsider");
    }

    private UserId persistUser(String uid, String username) {
        User user = User.register(new FirebaseUid(uid), new Email(username + "@example.com"),
                new Username(username), username, now());
        new JooqUserRepository(dsl, new AggregateWriter(dsl)).save(user);
        return user.id();
    }

    private Project persistedProject() {
        Project project = Project.create(new ProjectName("Chassis packing"), creator, now());
        repository().save(project);
        return project;
    }

    @Test
    void listsOnlyProjectsTheCallerBelongsTo() {
        Project mine = persistedProject();
        repository().save(Project.create(new ProjectName("Theirs"), member, now()));

        Page<ProjectSummaryView> page = finder().listForMember(creator, new PageRequest(0, 10));

        assertThat(page.content()).extracting(ProjectSummaryView::id)
                .containsExactly(mine.id().value());
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

        Page<ProjectSummaryView> page = finder().listForMember(creator, new PageRequest(0, 10));

        assertThat(page.content()).extracting(ProjectSummaryView::name)
                .containsExactly("Disabled", "Newer", "Chassis packing");
        assertThat(page.totalElements()).isEqualTo(3L);
    }

    @Test
    void listPagesAndReportsTheTotalIndependentlyOfThePageSize() {
        for (int i = 0; i < 5; i++) {
            repository().save(Project.create(new ProjectName("P" + i), creator,
                    now().plusSeconds(i)));
        }

        assertThat(finder().listForMember(creator, new PageRequest(0, 2)).content()).hasSize(2);
        assertThat(finder().listForMember(creator, new PageRequest(2, 2)).content()).hasSize(1);
        assertThat(finder().listForMember(creator, new PageRequest(5, 2)).content()).isEmpty();
        assertThat(finder().listForMember(creator, new PageRequest(5, 2)).totalElements())
                .isEqualTo(5L);
    }

    @Test
    void summaryCarriesTheMemberCountAndTheCallersOwnPermission() {
        Project project = persistedProject();
        project.grantAccess(member, ProjectPermission.WRITE, creator, now());
        repository().save(project);

        assertThat(finder().listForMember(member, new PageRequest(0, 10)).content())
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

        Page<ProjectSummaryView> page = finder().listForMember(creator, new PageRequest(0, 10));

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

        new JooqProjectFinder(counting).listForMember(creator, new PageRequest(0, 10));

        assertThat(statements).hasValue(2);
    }

    @Test
    void detailCarriesEveryMemberWithTheirIdentity() {
        Project project = persistedProject();
        project.grantAccess(member, ProjectPermission.WRITE, creator, now());
        repository().save(project);

        assertThat(finder().detailFor(creator, project.id())).hasValueSatisfying(view -> {
            assertThat(view.id()).isEqualTo(project.id().value());
            assertThat(view.createdBy()).isEqualTo(creator.value());
            assertThat(view.myPermission()).isEqualTo(ProjectPermission.OWNER);
            assertThat(view.members()).hasSize(2);
            assertThat(view.members()).extracting(m -> m.username())
                    .containsExactly("creator", "member");
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
                .hasValueSatisfying(view ->
                        assertThat(view.status()).isEqualTo(ProjectStatus.DISABLED));
    }
}
