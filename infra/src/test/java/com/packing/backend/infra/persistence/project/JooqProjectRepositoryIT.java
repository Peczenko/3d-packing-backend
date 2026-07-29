package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.shared.ConcurrentUpdateException;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.packing.backend.infra.persistence.jooq.tables.ProjectMembers.PROJECT_MEMBERS;
import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The compensating control for generating offline: the generator reverse engineers the
 * migrations through an in-memory H2 database, so only executing against real PostgreSQL
 * proves the two agree.
 */
@JooqTest
@Import(TestcontainersConfiguration.class)
class JooqProjectRepositoryIT {

    @Autowired
    private DSLContext dsl;

    private UserId creator;
    private UserId member;

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
    void savesAndReadsBackEveryFieldIncludingTheCreatorsMembership() {
        Project saved = persistedProject();

        Optional<Project> found = repository().findById(saved.id());

        assertThat(found).hasValueSatisfying(project -> {
            assertThat(project.id()).isEqualTo(saved.id());
            assertThat(project.name()).isEqualTo(new ProjectName("Chassis packing"));
            assertThat(project.createdBy()).isEqualTo(creator);
            assertThat(project.status()).isEqualTo(ProjectStatus.ACTIVE);
            assertThat(project.version()).isEqualTo(Project.INITIAL_VERSION + 1);
            assertThat(project.createdAt()).isEqualTo(saved.createdAt());
            assertThat(project.deletedAt()).isNull();
            assertThat(project.members()).singleElement().satisfies(only -> {
                assertThat(only.userId()).isEqualTo(creator);
                assertThat(only.permission()).isEqualTo(ProjectPermission.OWNER);
                assertThat(only.addedBy()).isEqualTo(creator);
            });
        });
    }

    @Test
    void findByIdIsEmptyForAnUnknownId() {
        assertThat(repository().findById(ProjectId.generate())).isEmpty();
    }

    @Test
    void membersAreReconciledOnEverySave() {
        Project project = persistedProject();

        project.grantAccess(member, ProjectPermission.WRITE, creator, now());
        repository().save(project);

        assertThat(repository().findById(project.id()).orElseThrow().members())
                .extracting(m -> m.userId(), m -> m.permission())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(creator, ProjectPermission.OWNER),
                        org.assertj.core.groups.Tuple.tuple(member, ProjectPermission.WRITE));
    }

    @Test
    void aRevokedMemberLeavesNoRowBehind() {
        Project project = persistedProject();
        project.grantAccess(member, ProjectPermission.WRITE, creator, now());
        repository().save(project);

        project.revokeAccess(member, now());
        repository().save(project);

        assertThat(repository().findById(project.id()).orElseThrow().members())
                .extracting(m -> m.userId())
                .containsExactly(creator);
        assertThat(dsl.fetchCount(dsl.selectFrom(PROJECT_MEMBERS)
                .where(PROJECT_MEMBERS.PROJECT_ID.eq(project.id().value())))).isEqualTo(1);
    }

    @Test
    void aReLevelledMemberKeepsTheOriginalGrantTimestamp() {
        Project project = persistedProject();
        Instant addedAt = now();
        project.grantAccess(member, ProjectPermission.READ, creator, addedAt);
        repository().save(project);

        project.grantAccess(member, ProjectPermission.OWNER, creator, addedAt.plusSeconds(60));
        repository().save(project);

        assertThat(repository().findById(project.id()).orElseThrow().members())
                .filteredOn(m -> m.userId().equals(member))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.permission()).isEqualTo(ProjectPermission.OWNER);
                    assertThat(m.addedAt()).isEqualTo(addedAt);
                });
    }

    @Test
    void aStaleWriteIsRejectedRatherThanClobberingAConcurrentChange() {
        Project project = persistedProject();

        Project stale = repository().findById(project.id()).orElseThrow();
        repository().save(stale);

        project.rename(new ProjectName("Renamed"), now());
        assertThatThrownBy(() -> repository().save(project))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessageContaining(project.id().toString());
    }

    /** The version guard runs before the member rewrite, so a stale save must not touch it. */
    @Test
    void aStaleWriteLeavesTheMemberRowsAlone() {
        Project project = persistedProject();
        Project stale = repository().findById(project.id()).orElseThrow();
        repository().save(stale);

        project.grantAccess(member, ProjectPermission.WRITE, creator, now());
        assertThatThrownBy(() -> repository().save(project))
                .isInstanceOf(ConcurrentUpdateException.class);

        assertThat(repository().findById(project.id()).orElseThrow().members())
                .extracting(m -> m.userId())
                .containsExactly(creator);
    }

    @Test
    void savingTwiceAdvancesTheVersionByExactlyOneEachTime() {
        Project project = Project.create(new ProjectName("Chassis"), creator, now());

        repository().save(project);
        assertThat(project.version()).isEqualTo(1L);

        project.rename(new ProjectName("Renamed"), now());
        repository().save(project);
        assertThat(project.version()).isEqualTo(2L);
        assertThat(repository().findById(project.id()).orElseThrow().version()).isEqualTo(2L);
    }

    @Test
    void deletingIsPersistedAsATombstoneRatherThanARowRemoval() {
        Project project = persistedProject();
        Instant deletedAt = now();

        project.delete(deletedAt);
        repository().save(project);

        assertThat(repository().findById(project.id())).hasValueSatisfying(found -> {
            assertThat(found.status()).isEqualTo(ProjectStatus.DELETED);
            assertThat(found.deletedAt()).isEqualTo(deletedAt);
        });
    }

    @Test
    void listingReturnsOnlyProjectsTheUserBelongsTo() {
        Project mine = persistedProject();
        Project theirs = Project.create(new ProjectName("Theirs"), member, now());
        repository().save(theirs);

        assertThat(repository().findByMember(creator, 0, 10))
                .extracting(project -> project.id())
                .containsExactly(mine.id());
        assertThat(repository().countByMember(creator)).isEqualTo(1L);
    }

    @Test
    void listingExcludesTombstonesAndReturnsNewestFirst() {
        Project older = persistedProject();
        Project newer = Project.create(new ProjectName("Newer"), creator, now().plusSeconds(1));
        repository().save(newer);
        Project removed = Project.create(new ProjectName("Removed"), creator, now());
        repository().save(removed);
        removed.delete(now());
        repository().save(removed);

        assertThat(repository().findByMember(creator, 0, 10))
                .extracting(project -> project.name().value())
                .containsExactly("Newer", "Chassis packing");
        assertThat(repository().countByMember(creator)).isEqualTo(2L);
    }

    @Test
    void listingPagesWithOffsetAndLimit() {
        for (int i = 0; i < 5; i++) {
            repository().save(Project.create(new ProjectName("P" + i), creator,
                    now().plusSeconds(i)));
        }

        assertThat(repository().findByMember(creator, 0, 2)).hasSize(2);
        assertThat(repository().findByMember(creator, 4, 2)).hasSize(1);
        assertThat(repository().findByMember(creator, 10, 2)).isEmpty();
        assertThat(repository().countByMember(creator)).isEqualTo(5L);
    }

    @Test
    void listingLoadsEveryProjectsMembersWithoutMixingThemUp() {
        Project first = persistedProject();
        first.grantAccess(member, ProjectPermission.READ, creator, now());
        repository().save(first);
        Project second = Project.create(new ProjectName("Second"), creator, now().plusSeconds(1));
        repository().save(second);

        List<Project> found = repository().findByMember(creator, 0, 10);

        assertThat(found).filteredOn(p -> p.id().equals(first.id()))
                .singleElement()
                .satisfies(p -> assertThat(p.members()).hasSize(2));
        assertThat(found).filteredOn(p -> p.id().equals(second.id()))
                .singleElement()
                .satisfies(p -> assertThat(p.members()).hasSize(1));
    }

    /**
     * The roster arrives through a correlated MULTISET rather than a follow-up query. Worth
     * pinning: the obvious refactor back to "fetch projects, then fetch their members" is
     * silently wrong under {@code READ COMMITTED}, because the two statements see two
     * snapshots and the roster can then contradict the version the optimistic lock writes on.
     */
    @Test
    void aPageOfProjectsAndEveryRosterIsOneStatement() {
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

        List<Project> found = new JooqProjectRepository(counting, new AggregateWriter(counting))
                .findByMember(creator, 0, 10);

        assertThat(found).singleElement()
                .satisfies(p -> assertThat(p.members()).hasSize(2));
        assertThat(statements).hasValue(1);
    }

    @Test
    void loadingOneProjectWithItsRosterIsOneStatement() {
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

        assertThat(new JooqProjectRepository(counting, new AggregateWriter(counting))
                .findById(project.id()))
                .hasValueSatisfying(p -> assertThat(p.members()).hasSize(1));
        assertThat(statements).hasValue(1);
    }

    @Test
    void theCreatorForeignKeyRejectsAProjectWithNoSuchUser() {
        Project orphan = Project.create(new ProjectName("Orphan"), UserId.generate(), now());

        assertThatThrownBy(() -> repository().save(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_projects_created_by");
    }

    @Test
    void theMemberForeignKeyRejectsAMembershipForNoSuchUser() {
        Project project = persistedProject();
        project.grantAccess(UserId.generate(), ProjectPermission.READ, creator, now());

        assertThatThrownBy(() -> repository().save(project))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_project_members_user");
    }

    @Test
    void theStatusCheckConstraintRejectsAValueOutsideTheEnum() {
        Project project = persistedProject();

        assertThatThrownBy(() -> dsl.update(PROJECTS)
                .set(PROJECTS.STATUS, "BOGUS")
                .where(PROJECTS.ID.eq(project.id().value()))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_projects_status");
    }

    @Test
    void thePermissionCheckConstraintRejectsAValueOutsideTheEnum() {
        Project project = persistedProject();

        assertThatThrownBy(() -> dsl.update(PROJECT_MEMBERS)
                .set(PROJECT_MEMBERS.PERMISSION, "SUPERUSER")
                .where(PROJECT_MEMBERS.PROJECT_ID.eq(project.id().value()))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_project_members_permission");
    }
}
