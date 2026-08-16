package com.packing.backend.domain.project;

import com.packing.backend.domain.project.event.ProjectAccessGranted;
import com.packing.backend.domain.shared.PermissionDeniedException;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectTest {

    private static final Instant     NOW     = Instant.parse("2026-07-27T10:15:30Z");
    private static final Instant     LATER   = NOW.plusSeconds(3600);
    private static final UserId      CREATOR = UserId.generate();
    private static final UserId      OTHER   = UserId.generate();
    private static final ProjectName NAME    = new ProjectName("Chassis packing");

    private Project activeProject() {
        Project project = Project.create(NAME, CREATOR, NOW);
        project.pullDomainEvents();
        return project;
    }

    @Test
    void createStartsActiveWithTheCreatorAsTheOnlyOwner() {
        Project project = Project.create(NAME, CREATOR, NOW);

        assertThat(project.status()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(project.createdBy()).isEqualTo(CREATOR);
        assertThat(project.version()).isEqualTo(Project.INITIAL_VERSION);
        assertThat(project.members()).singleElement()
                                     .satisfies(member -> {
                                         assertThat(member.userId()).isEqualTo(CREATOR);
                                         assertThat(member.permission()).isEqualTo(ProjectPermission.OWNER);
                                         assertThat(member.addedBy()).isEqualTo(CREATOR);
                                     });
    }

    @Test
    void createRecordsNoEventBecauseNobodyNeedsWelcomingToTheirOwnProject() {
        assertThat(Project.create(NAME, CREATOR, NOW)
                          .domainEvents()).isEmpty();
    }

    @Test
    void grantingAccessToANewMemberRecordsTheEventTheNotificationNeeds() {
        Project project = activeProject();

        project.grantAccess(OTHER, ProjectPermission.WRITE, CREATOR, LATER);

        assertThat(project.permissionOf(OTHER)).contains(ProjectPermission.WRITE);
        assertThat(project.domainEvents()).singleElement()
                                          .isInstanceOfSatisfying(ProjectAccessGranted.class, event -> {
                                              assertThat(event.projectId()).isEqualTo(project.id());
                                              assertThat(event.projectName()).isEqualTo(NAME);
                                              assertThat(event.userId()).isEqualTo(OTHER);
                                              assertThat(event.permission()).isEqualTo(ProjectPermission.WRITE);
                                              assertThat(event.grantedBy()).isEqualTo(CREATOR);
                                              assertThat(event.occurredAt()).isEqualTo(LATER);
                                          });
    }

    @Test
    void reLevellingAnExistingMemberRecordsNoEvent() {
        Project project = activeProject();
        project.grantAccess(OTHER, ProjectPermission.READ, CREATOR, LATER);
        project.pullDomainEvents();

        project.grantAccess(OTHER, ProjectPermission.WRITE, CREATOR, LATER);

        assertThat(project.permissionOf(OTHER)).contains(ProjectPermission.WRITE);
        assertThat(project.domainEvents()).isEmpty();
    }

    @Test
    void grantingTheSamePermissionTwiceIsANoOp() {
        Project project = activeProject();
        project.grantAccess(OTHER, ProjectPermission.READ, CREATOR, LATER);
        project.pullDomainEvents();

        project.grantAccess(OTHER, ProjectPermission.READ, CREATOR, LATER.plusSeconds(60));

        assertThat(project.domainEvents()).isEmpty();
    }

    @Test
    void theLastOwnerCannotBeDemoted() {
        Project project = activeProject();
        project.grantAccess(OTHER, ProjectPermission.WRITE, CREATOR, LATER);

        assertThatThrownBy(() -> project.grantAccess(CREATOR, ProjectPermission.READ, CREATOR, LATER))
                                                                                                      .isInstanceOf(ResourceConflictException.class)
                                                                                                      .hasMessageContaining("at least one owner");
        assertThat(project.permissionOf(CREATOR)).contains(ProjectPermission.OWNER);
    }

    @Test
    void theLastOwnerCannotBeRemoved() {
        Project project = activeProject();

        assertThatThrownBy(() -> project.revokeAccess(CREATOR, LATER))
                                                                      .isInstanceOf(ResourceConflictException.class)
                                                                      .hasMessageContaining("at least one owner");
        assertThat(project.members()).hasSize(1);
    }

    @Test
    void theCreatorCanBeDemotedOnceAnotherOwnerExists() {
        Project project = activeProject();
        project.grantAccess(OTHER, ProjectPermission.OWNER, CREATOR, LATER);

        project.grantAccess(CREATOR, ProjectPermission.READ, OTHER, LATER);

        assertThat(project.permissionOf(CREATOR)).contains(ProjectPermission.READ);
        assertThat(project.createdBy()).isEqualTo(CREATOR);
    }

    @Test
    void revokingANonMemberIsANoOp() {
        Project project = activeProject();

        project.revokeAccess(UserId.generate(), LATER);

        assertThat(project.members()).hasSize(1);
    }

    @Test
    void requireAccessReportsANonMemberAsMissingRatherThanForbidden() {
        Project project = activeProject();

        assertThatThrownBy(() -> project.requireAccess(OTHER, ProjectPermission.READ))
                                                                                      .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void requireAccessReportsAnUnderprivilegedMemberAsForbidden() {
        Project project = activeProject();
        project.grantAccess(OTHER, ProjectPermission.READ, CREATOR, LATER);

        assertThatThrownBy(() -> project.requireAccess(OTHER, ProjectPermission.WRITE))
                                                                                       .isInstanceOf(PermissionDeniedException.class)
                                                                                       .hasMessageContaining("WRITE");
    }

    @Test
    void requireAccessReturnsTheActualLevelWhenItSuffices() {
        Project project = activeProject();
        project.grantAccess(OTHER, ProjectPermission.OWNER, CREATOR, LATER);

        assertThat(project.requireAccess(OTHER, ProjectPermission.READ))
                                                                        .isEqualTo(ProjectPermission.OWNER);
    }

    @Test
    void disablingBlocksEveryWriteButLeavesTheProjectReadable() {
        Project project = activeProject();

        project.disable(LATER);

        assertThat(project.status()).isEqualTo(ProjectStatus.DISABLED);
        assertThat(project.requireAccess(CREATOR, ProjectPermission.OWNER))
                                                                           .isEqualTo(ProjectPermission.OWNER);
        assertThatThrownBy(() -> project.rename(new ProjectName("New"), LATER))
                                                                               .isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(() -> project.grantAccess(OTHER, ProjectPermission.READ, CREATOR, LATER))
                                                                                                    .isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(() -> project.revokeAccess(CREATOR, LATER))
                                                                      .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void activatingRestoresWrites() {
        Project project = activeProject();
        project.disable(LATER);

        project.activate(LATER.plusSeconds(60));

        assertThat(project.status()).isEqualTo(ProjectStatus.ACTIVE);
        project.rename(new ProjectName("New"), LATER.plusSeconds(60));
        assertThat(project.name()).isEqualTo(new ProjectName("New"));
    }

    @Test
    void disablingAndActivatingAreIdempotent() {
        Project project = activeProject();

        project.disable(LATER);
        project.disable(LATER.plusSeconds(60));
        assertThat(project.updatedAt()).isEqualTo(LATER);

        project.activate(LATER.plusSeconds(120));
        project.activate(LATER.plusSeconds(180));
        assertThat(project.updatedAt()).isEqualTo(LATER.plusSeconds(120));
    }

    @Test
    void aDisabledProjectCanStillBeDeleted() {
        Project project = activeProject();
        project.disable(LATER);

        project.delete(LATER.plusSeconds(60));

        assertThat(project.isDeleted()).isTrue();
        assertThat(project.deletedAt()).isEqualTo(LATER.plusSeconds(60));
    }

    @Test
    void deleteIsIdempotent() {
        Project project = activeProject();

        project.delete(LATER);
        project.delete(LATER.plusSeconds(60));

        assertThat(project.deletedAt()).isEqualTo(LATER);
    }

    @Test
    void aDeletedProjectCannotBeDisabledOrActivated() {
        Project project = activeProject();
        project.delete(LATER);

        assertThatThrownBy(() -> project.disable(LATER)).isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(() -> project.activate(LATER)).isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void renamingToTheSameNameIsANoOp() {
        Project project = activeProject();

        project.rename(NAME, LATER);

        assertThat(project.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void membersAreNotWritableThroughTheReturnedList() {
        Project project = activeProject();

        assertThatThrownBy(() -> project.members()
                                        .clear())
                                                 .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void markPersistedAdvancesTheOptimisticLock() {
        Project project = activeProject();

        project.markPersisted();

        assertThat(project.version()).isEqualTo(Project.INITIAL_VERSION + 1);
    }

    @Test
    void identityIsTheIdAlone() {
        Project project = activeProject();
        Project sameId = Project.rehydrate(project.id(),
                                           new ProjectName("Different"),
                                           OTHER,
                                           ProjectStatus.DISABLED,
                                           9L,
                                           NOW,
                                           LATER,
                                           null,
                                           project.members());

        assertThat(project).isEqualTo(sameId)
                           .hasSameHashCodeAs(sameId);
    }
}
