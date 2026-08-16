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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.packing.backend.infra.persistence.jooq.tables.ProjectMembers.PROJECT_MEMBERS;
import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;
import static com.packing.backend.infra.persistence.shared.RawColumns.untyped;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        return Instant.now()
                      .truncatedTo(ChronoUnit.MICROS);
    }

    @BeforeEach
    void createUsers() {
        creator = persistUser("uid-creator", "creator");
        member = persistUser("uid-member", "member");
    }

    private UserId persistUser(String uid, String username) {
        User user = User.register(new FirebaseUid(uid),
                                  new Email(username + "@example.com"),
                                  new Username(username),
                                  username,
                                  now());
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
            assertThat(project.members()).singleElement()
                                         .satisfies(only -> {
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

        assertThat(repository().findById(project.id())
                               .orElseThrow()
                               .members())
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

        assertThat(repository().findById(project.id())
                               .orElseThrow()
                               .members())
                                          .extracting(m -> m.userId())
                                          .containsExactly(creator);
        assertThat(dsl.fetchCount(dsl.selectFrom(PROJECT_MEMBERS)
                                     .where(PROJECT_MEMBERS.PROJECT_ID.eq(project.id())))).isEqualTo(1);
    }

    @Test
    void aReLevelledMemberKeepsTheOriginalGrantTimestamp() {
        Project project = persistedProject();
        Instant addedAt = now();
        project.grantAccess(member, ProjectPermission.READ, creator, addedAt);
        repository().save(project);

        project.grantAccess(member, ProjectPermission.OWNER, creator, addedAt.plusSeconds(60));
        repository().save(project);

        assertThat(repository().findById(project.id())
                               .orElseThrow()
                               .members())
                                          .filteredOn(m -> m.userId()
                                                            .equals(member))
                                          .singleElement()
                                          .satisfies(m -> {
                                              assertThat(m.permission()).isEqualTo(ProjectPermission.OWNER);
                                              assertThat(m.addedAt()).isEqualTo(addedAt);
                                          });
    }

    @Test
    void aStaleWriteIsRejectedRatherThanClobberingAConcurrentChange() {
        Project project = persistedProject();

        Project stale = repository().findById(project.id())
                                    .orElseThrow();
        repository().save(stale);

        project.rename(new ProjectName("Renamed"), now());
        assertThatThrownBy(() -> repository().save(project))
                                                            .isInstanceOf(ConcurrentUpdateException.class)
                                                            .hasMessageContaining(project.id()
                                                                                         .toString());
    }

    @Test
    void aStaleWriteLeavesTheMemberRowsAlone() {
        Project project = persistedProject();
        Project stale = repository().findById(project.id())
                                    .orElseThrow();
        repository().save(stale);

        project.grantAccess(member, ProjectPermission.WRITE, creator, now());
        assertThatThrownBy(() -> repository().save(project))
                                                            .isInstanceOf(ConcurrentUpdateException.class);

        assertThat(repository().findById(project.id())
                               .orElseThrow()
                               .members())
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
        assertThat(repository().findById(project.id())
                               .orElseThrow()
                               .version()).isEqualTo(2L);
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
                                    .set(untyped(PROJECTS.STATUS), "BOGUS")
                                    .where(PROJECTS.ID.eq(project.id()))
                                    .execute())
                                               .isInstanceOf(DataIntegrityViolationException.class)
                                               .hasMessageContaining("ck_projects_status");
    }

    @Test
    void thePermissionCheckConstraintRejectsAValueOutsideTheEnum() {
        Project project = persistedProject();

        assertThatThrownBy(() -> dsl.update(PROJECT_MEMBERS)
                                    .set(untyped(PROJECT_MEMBERS.PERMISSION), "SUPERUSER")
                                    .where(PROJECT_MEMBERS.PROJECT_ID.eq(project.id()))
                                    .execute())
                                               .isInstanceOf(DataIntegrityViolationException.class)
                                               .hasMessageContaining("ck_project_members_permission");
    }
}
