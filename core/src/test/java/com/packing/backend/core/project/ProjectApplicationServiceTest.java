package com.packing.backend.core.project;

import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.project.ProjectApplicationService.ChangeAccessCommand;
import com.packing.backend.core.project.ProjectApplicationService.CreateProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.GrantAccessCommand;
import com.packing.backend.core.project.ProjectApplicationService.ListProjectsCommand;
import com.packing.backend.core.project.ProjectApplicationService.ProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.ProjectQuery;
import com.packing.backend.core.project.ProjectApplicationService.RenameProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.RevokeAccessCommand;
import com.packing.backend.core.project.port.out.ProjectFinder;
import com.packing.backend.core.project.port.out.ProjectRepository;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.port.out.ActiveUserLookup;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.file.event.FileDeleted;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.project.ProjectNotFoundException;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.project.event.ProjectAccessGranted;
import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.shared.PermissionDeniedException;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserNotFoundException;
import com.packing.backend.domain.user.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectApplicationServiceTest {

    private static final Instant     NOW    = Instant.parse("2026-07-27T10:15:30Z");
    private static final String      UID    = "firebase-uid-1";
    private static final UserId      CALLER = UserId.generate();
    private static final UserId      MEMBER = UserId.generate();
    private static final ProjectName NAME   = new ProjectName("Chassis packing");

    @Mock
    private ProjectRepository    projects;
    @Mock
    private ProjectFinder        projectFinder;
    @Mock
    private UserRepository       users;
    @Mock
    private ActiveUserLookup     activeUsers;
    @Mock
    private FileRepository       files;
    @Mock
    private DomainEventPublisher eventPublisher;

    private ProjectApplicationService service;

    private final Map<ProjectId, Project> stored = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new ProjectApplicationService(projects,
                                                projectFinder,
                                                users,
                                                activeUsers,
                                                files,
                                                eventPublisher,
                                                Clock.fixed(NOW, ZoneOffset.UTC));
        when(activeUsers.findActiveUser(new FirebaseUid(UID))).thenReturn(Optional.of(CALLER));
        when(projects.save(any())).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            stored.put(project.id(), project);
            return project;
        });
        when(projectFinder.detailFor(any(), any())).thenAnswer(invocation -> {
            UserId caller = invocation.getArgument(0);
            ProjectId id = invocation.getArgument(1);
            Project project = stored.get(id);
            if (project == null || project.isDeleted()
                    || project.permissionOf(caller)
                              .isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(viewOf(project, caller));
        });
    }

    private static ProjectView viewOf(Project project, UserId caller) {
        List<ProjectMemberView> members = project.members()
                                                 .stream()
                                                 .map(member -> new ProjectMemberView(member.userId()
                                                                                            .value(),
                                                                                      "user" + Math.abs(member.userId()
                                                                                                              .value()
                                                                                                              .hashCode()),
                                                                                      "Display Name",
                                                                                      member.permission(),
                                                                                      member.addedAt()))
                                                 .sorted(Comparator.comparing(ProjectMemberView::addedAt))
                                                 .toList();
        return new ProjectView(project.id()
                                      .value(),
                               project.name()
                                      .value(),
                               project.status(),
                               project.createdBy()
                                      .value(),
                               project.permissionOf(caller)
                                      .orElseThrow(),
                               members,
                               project.createdAt(),
                               project.updatedAt());
    }

    private static User userWithId(UserId id) {
        User user = User.rehydrate(id,
                                   new FirebaseUid("uid-" + id.value()),
                                   new Email("u" + Math.abs(id.value()
                                                              .hashCode())
                                           + "@example.com"),
                                   Username.suggestionFrom("user" + Math.abs(id.value()
                                                                               .hashCode())),
                                   "Display Name",
                                   com.packing.backend.domain.user.UserRole.USER,
                                   com.packing.backend.domain.user.UserStatus.ACTIVE,
                                   1L,
                                   NOW,
                                   NOW,
                                   null);
        return user;
    }

    private Project ownedProject() {
        Project project = Project.create(NAME, CALLER, NOW);
        project.pullDomainEvents();
        when(projects.findById(project.id())).thenReturn(Optional.of(project));
        stored.put(project.id(), project);
        return project;
    }

    private Project projectWhereCallerIs(ProjectPermission permission) {
        Project project = Project.create(NAME, MEMBER, NOW);
        project.grantAccess(CALLER, permission, MEMBER, NOW);
        project.pullDomainEvents();
        when(projects.findById(project.id())).thenReturn(Optional.of(project));
        stored.put(project.id(), project);
        return project;
    }

    @Test
    void createMakesTheCallerTheFirstOwner() {
        ProjectView view = service.createProject(new CreateProjectCommand(UID, "Chassis packing"));

        assertThat(view.name()).isEqualTo("Chassis packing");
        assertThat(view.status()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(view.createdBy()).isEqualTo(CALLER.value());
        assertThat(view.myPermission()).isEqualTo(ProjectPermission.OWNER);
        assertThat(view.members()).singleElement()
                                  .satisfies(member -> {
                                      assertThat(member.userId()).isEqualTo(CALLER.value());
                                      assertThat(member.permission()).isEqualTo(ProjectPermission.OWNER);
                                  });
    }

    @Test
    void createRejectsACallerWithNoActiveProfile() {
        when(activeUsers.findActiveUser(new FirebaseUid(UID))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createProject(new CreateProjectCommand(UID, "X")))
                                                                                           .isInstanceOf(UserNotFoundException.class);

        verify(projects, never()).save(any());
    }

    @Test
    void renameRequiresOwnership() {
        Project project = projectWhereCallerIs(ProjectPermission.WRITE);

        assertThatThrownBy(() -> service.renameProject(
                                                       new RenameProjectCommand(UID,
                                                                                project.id()
                                                                                       .value(),
                                                                                "New name")))
                                                                                             .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void renameSucceedsForAnOwner() {
        Project project = ownedProject();

        ProjectView view = service.renameProject(
                                                 new RenameProjectCommand(UID,
                                                                          project.id()
                                                                                 .value(),
                                                                          "New name"));

        assertThat(view.name()).isEqualTo("New name");
    }

    @Test
    void aDeletedProjectIsIndistinguishableFromOneThatNeverExisted() {
        Project project = ownedProject();
        project.delete(NOW);

        assertThatThrownBy(() -> service.getProject(
                                                    new ProjectQuery(UID,
                                                                     project.id()
                                                                            .value())))
                                                                                       .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void anUnknownProjectIsNotFound() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.getProject(new ProjectQuery(UID, unknown)))
                                                                                    .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void aNonMemberSeesTheSameNotFoundRatherThanAForbidden() {
        Project project = Project.create(NAME, MEMBER, NOW);
        stored.put(project.id(), project);

        assertThatThrownBy(() -> service.getProject(
                                                    new ProjectQuery(UID,
                                                                     project.id()
                                                                            .value())))
                                                                                       .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void listProjectsDelegatesToTheFinderWithTheCallersId() {
        ProjectSummaryView summary = new ProjectSummaryView(UUID.randomUUID(),
                                                            "Chassis packing",
                                                            ProjectStatus.ACTIVE,
                                                            ProjectPermission.OWNER,
                                                            1,
                                                            NOW,
                                                            NOW);
        when(projectFinder.listForMember(CALLER, new PageRequest(2, 20)))
                                                                         .thenReturn(new Page<>(List.of(summary), 2, 20, 45L));

        Page<ProjectSummaryView> page = service.listProjects(
                                                             new ListProjectsCommand(UID, new PageRequest(2, 20)));

        assertThat(page.content()).containsExactly(summary);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void grantAccessResolvesTheMemberByEmail() {
        Project project = ownedProject();
        User invitee = userWithId(MEMBER);
        when(users.findByEmail(new Email("bob@example.com"))).thenReturn(Optional.of(invitee));

        ProjectView view = service.grantAccess(new GrantAccessCommand(
                                                                      UID,
                                                                      project.id()
                                                                             .value(),
                                                                      "bob@example.com",
                                                                      ProjectPermission.WRITE));

        assertThat(view.members()).hasSize(2);
        assertThat(project.permissionOf(MEMBER)).contains(ProjectPermission.WRITE);
    }

    @Test
    void grantAccessFallsBackToTheUsername() {
        Project project = ownedProject();
        User invitee = userWithId(MEMBER);
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(users.findByUsername(new Username("bob"))).thenReturn(Optional.of(invitee));

        service.grantAccess(new GrantAccessCommand(
                                                   UID,
                                                   project.id()
                                                          .value(),
                                                   "bob",
                                                   ProjectPermission.READ));

        assertThat(project.permissionOf(MEMBER)).contains(ProjectPermission.READ);
    }

    @Test
    void grantAccessPublishesTheEventThatDrivesTheNotification() {
        Project project = ownedProject();
        when(users.findByEmail(new Email("bob@example.com")))
                                                             .thenReturn(Optional.of(userWithId(MEMBER)));

        service.grantAccess(new GrantAccessCommand(
                                                   UID,
                                                   project.id()
                                                          .value(),
                                                   "bob@example.com",
                                                   ProjectPermission.WRITE));

        assertThat(publishedEvents()).singleElement()
                                     .isInstanceOfSatisfying(ProjectAccessGranted.class, event -> {
                                         assertThat(event.userId()).isEqualTo(MEMBER);
                                         assertThat(event.grantedBy()).isEqualTo(CALLER);
                                         assertThat(event.permission()).isEqualTo(ProjectPermission.WRITE);
                                     });
    }

    @Test
    void grantAccessReportsAnUnknownIdentifierWithoutConfirmingIt() {
        Project project = ownedProject();
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(users.findByUsername(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantAccess(new GrantAccessCommand(
                                                                            UID,
                                                                            project.id()
                                                                                   .value(),
                                                                            "ghost@example.com",
                                                                            ProjectPermission.READ)))
                                                                                                     .isInstanceOf(UserNotFoundException.class)
                                                                                                     .hasMessage("No user matches that identifier");
    }

    @Test
    void grantAccessRejectsAnIdentifierThatIsNeitherAnEmailNorAUsername() {
        Project project = ownedProject();

        assertThatThrownBy(() -> service.grantAccess(new GrantAccessCommand(
                                                                            UID,
                                                                            project.id()
                                                                                   .value(),
                                                                            "!!",
                                                                            ProjectPermission.READ)))
                                                                                                     .isInstanceOf(UserNotFoundException.class)
                                                                                                     .hasMessage("No user matches that identifier");
    }

    @Test
    void grantAccessRequiresOwnership() {
        Project project = projectWhereCallerIs(ProjectPermission.WRITE);

        assertThatThrownBy(() -> service.grantAccess(new GrantAccessCommand(
                                                                            UID,
                                                                            project.id()
                                                                                   .value(),
                                                                            "bob@example.com",
                                                                            ProjectPermission.READ)))
                                                                                                     .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void changeAccessRefusesToAddSomebodyWhoIsNotAlreadyAMember() {
        Project project = ownedProject();

        assertThatThrownBy(() -> service.changeAccess(new ChangeAccessCommand(
                                                                              UID,
                                                                              project.id()
                                                                                     .value(),
                                                                              MEMBER.value(),
                                                                              ProjectPermission.WRITE)))
                                                                                                        .isInstanceOf(UserNotFoundException.class);

        assertThat(project.permissionOf(MEMBER)).isEmpty();
    }

    @Test
    void changeAccessReLevelsAnExistingMemberWithoutANewEvent() {
        Project project = ownedProject();
        project.grantAccess(MEMBER, ProjectPermission.READ, CALLER, NOW);
        project.pullDomainEvents();

        service.changeAccess(new ChangeAccessCommand(
                                                     UID,
                                                     project.id()
                                                            .value(),
                                                     MEMBER.value(),
                                                     ProjectPermission.WRITE));

        assertThat(project.permissionOf(MEMBER)).contains(ProjectPermission.WRITE);
        assertThat(publishedEvents()).isEmpty();
    }

    @Test
    void changeAccessCannotDemoteTheLastOwner() {
        Project project = ownedProject();
        project.grantAccess(MEMBER, ProjectPermission.WRITE, CALLER, NOW);
        project.pullDomainEvents();

        assertThatThrownBy(() -> service.changeAccess(new ChangeAccessCommand(
                                                                              UID,
                                                                              project.id()
                                                                                     .value(),
                                                                              CALLER.value(),
                                                                              ProjectPermission.READ)))
                                                                                                       .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void aMemberCanRemoveThemselvesWithoutBeingAnOwner() {
        Project project = projectWhereCallerIs(ProjectPermission.READ);

        service.revokeAccess(new RevokeAccessCommand(
                                                     UID,
                                                     project.id()
                                                            .value(),
                                                     CALLER.value()));

        assertThat(project.permissionOf(CALLER)).isEmpty();
    }

    @Test
    void aMemberCannotRemoveSomebodyElse() {
        Project project = projectWhereCallerIs(ProjectPermission.WRITE);

        assertThatThrownBy(() -> service.revokeAccess(new RevokeAccessCommand(
                                                                              UID,
                                                                              project.id()
                                                                                     .value(),
                                                                              MEMBER.value())))
                                                                                               .isInstanceOf(PermissionDeniedException.class);

        assertThat(project.permissionOf(MEMBER)).contains(ProjectPermission.OWNER);
    }

    @Test
    void anOwnerCanRemoveAnyMember() {
        Project project = ownedProject();
        project.grantAccess(MEMBER, ProjectPermission.WRITE, CALLER, NOW);
        project.pullDomainEvents();

        service.revokeAccess(new RevokeAccessCommand(
                                                     UID,
                                                     project.id()
                                                            .value(),
                                                     MEMBER.value()));

        assertThat(project.permissionOf(MEMBER)).isEmpty();
    }

    @Test
    void theLastOwnerCannotLeave() {
        Project project = ownedProject();

        assertThatThrownBy(() -> service.revokeAccess(new RevokeAccessCommand(
                                                                              UID,
                                                                              project.id()
                                                                                     .value(),
                                                                              CALLER.value())))
                                                                                               .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void disableAndActivateRequireOwnership() {
        Project project = projectWhereCallerIs(ProjectPermission.WRITE);
        UUID id = project.id()
                         .value();

        assertThatThrownBy(() -> service.disableProject(new ProjectCommand(UID, id)))
                                                                                     .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> service.activateProject(new ProjectCommand(UID, id)))
                                                                                      .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void disableMakesTheProjectAReadOnlyArchive() {
        Project project = ownedProject();

        service.disableProject(new ProjectCommand(UID,
                                                  project.id()
                                                         .value()));

        assertThat(project.status()).isEqualTo(ProjectStatus.DISABLED);
        assertThat(service.getProject(new ProjectQuery(UID,
                                                       project.id()
                                                              .value()))
                          .status())
                                    .isEqualTo(ProjectStatus.DISABLED);
    }

    @Test
    void deleteCascadesToEveryFileAndPublishesOneEventPerBlobToReclaim() {
        Project project = ownedProject();
        StoredFile first = fileIn(project.id(), "a.stl");
        StoredFile second = fileIn(project.id(), "b.stl");
        when(files.findAllAvailableByProject(project.id()))
                                                           .thenReturn(List.of(first, second));
        when(files.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.deleteProject(new ProjectCommand(UID,
                                                 project.id()
                                                        .value()));

        assertThat(project.isDeleted()).isTrue();
        assertThat(first.isDeleted()).isTrue();
        assertThat(second.isDeleted()).isTrue();
        assertThat(publishedEvents())
                                     .hasSize(2)
                                     .allSatisfy(event -> assertThat(event).isInstanceOf(FileDeleted.class));
    }

    @Test
    void deleteRequiresOwnership() {
        Project project = projectWhereCallerIs(ProjectPermission.WRITE);

        assertThatThrownBy(() -> service.deleteProject(
                                                       new ProjectCommand(UID,
                                                                          project.id()
                                                                                 .value())))
                                                                                            .isInstanceOf(PermissionDeniedException.class);

        verify(files, never()).saveAll(any());
    }

    private StoredFile fileIn(ProjectId projectId, String name) {
        return StoredFile.upload(FileId.generate(),
                                 CALLER,
                                 projectId,
                                 new FileName(name),
                                 1_024L,
                                 Checksum.ofHex("a".repeat(64)),
                                 NOW);
    }

    private List<DomainEvent> publishedEvents() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<? extends DomainEvent>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(eventPublisher).publishAll(captor.capture());
        return List.copyOf(captor.getValue());
    }
}
