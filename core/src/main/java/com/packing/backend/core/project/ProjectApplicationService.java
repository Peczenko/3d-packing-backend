package com.packing.backend.core.project;

import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.project.port.out.ProjectFinder;
import com.packing.backend.core.project.port.out.ProjectRepository;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.port.out.ActiveUserLookup;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.project.ProjectNotFoundException;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.shared.PermissionDeniedException;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class ProjectApplicationService {

    private final ProjectRepository    projects;
    private final ProjectFinder        projectFinder;
    private final UserRepository       users;
    private final ActiveUserLookup     activeUsers;
    private final FileRepository       files;
    private final DomainEventPublisher eventPublisher;
    private final Clock                clock;

    public ProjectView createProject(CreateProjectCommand command) {
        Instant now = clock.instant();
        UserId caller = requireActiveCaller(command.firebaseUid());

        Project project = Project.create(new ProjectName(command.name()), caller, now);
        saveAndPublish(project);
        return viewOf(caller, project.id());
    }

    @Transactional(readOnly = true)
    public Page<ProjectSummaryView> listProjects(ListProjectsCommand command) {
        UserId caller = requireActiveCaller(command.firebaseUid());
        return projectFinder.listForMember(caller, command.criteria());
    }

    @Transactional(readOnly = true)
    public Page<ProjectMemberView> listProjectMembers(ListProjectMembersQuery query) {
        UserId caller = requireActiveCaller(query.firebaseUid());
        ProjectId projectId = new ProjectId(query.projectId());
        viewOf(caller, projectId);
        return projectFinder.listMembersFor(caller, projectId, query.criteria());
    }

    @Transactional(readOnly = true)
    public ProjectView getProject(ProjectQuery query) {
        UserId caller = requireActiveCaller(query.firebaseUid());
        return viewOf(caller, new ProjectId(query.projectId()));
    }

    public ProjectView renameProject(RenameProjectCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(),
                                      command.projectId(),
                                      ProjectPermission.OWNER);

        access.project()
              .rename(new ProjectName(command.name()), now);
        saveAndPublish(access.project());
        return viewOf(access.caller(),
                      access.project()
                            .id());
    }

    public void disableProject(ProjectCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(),
                                      command.projectId(),
                                      ProjectPermission.OWNER);

        access.project()
              .disable(now);
        saveAndPublish(access.project());
    }

    public void activateProject(ProjectCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(),
                                      command.projectId(),
                                      ProjectPermission.OWNER);

        access.project()
              .activate(now);
        saveAndPublish(access.project());
    }

    public void deleteProject(ProjectCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(),
                                      command.projectId(),
                                      ProjectPermission.OWNER);

        ProjectId projectId = new ProjectId(command.projectId());
        List<StoredFile> contents = files.findAllAvailableByProject(projectId);
        contents.forEach(file -> file.delete(now));

        access.project()
              .delete(now);
        projects.save(access.project());

        List<DomainEvent> events = new ArrayList<>(access.project()
                                                         .pullDomainEvents());
        files.saveAll(contents)
             .forEach(file -> events.addAll(file.pullDomainEvents()));
        eventPublisher.publishAll(events);
    }

    public ProjectView grantAccess(GrantAccessCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(),
                                      command.projectId(),
                                      ProjectPermission.OWNER);

        User member = resolveMember(new UserId(command.userId()));
        access.project()
              .grantAccess(member.id(), command.permission(), access.caller(), now);
        saveAndPublish(access.project());
        return viewOf(access.caller(),
                      access.project()
                            .id());
    }

    public ProjectView changeAccess(ChangeAccessCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(),
                                      command.projectId(),
                                      ProjectPermission.OWNER);

        UserId target = new UserId(command.userId());
        if (access.project()
                  .permissionOf(target)
                  .isEmpty()) {
            throw new UserNotFoundException("No member of this project with id " + target);
        }

        access.project()
              .grantAccess(target, command.permission(), access.caller(), now);
        saveAndPublish(access.project());
        return viewOf(access.caller(),
                      access.project()
                            .id());
    }

    public void revokeAccess(RevokeAccessCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(),
                                      command.projectId(),
                                      ProjectPermission.READ);

        UserId target = new UserId(command.userId());
        if (!target.equals(access.caller())
                && !access.permission()
                          .allows(ProjectPermission.OWNER)) {
            throw new PermissionDeniedException(
                                                "This action requires OWNER permission on project " + command.projectId());
        }

        access.project()
              .revokeAccess(target, now);
        saveAndPublish(access.project());
    }

    private User resolveMember(UserId userId) {
        User user = users.findById(userId)
                         .filter(candidate -> !candidate.isDeleted())
                         .orElseThrow(() -> new UserNotFoundException("No active user matches that id"));
        if (!user.isActive()) {
            throw new DomainRuleViolationException("Cannot add a disabled user to a project");
        }
        return user;
    }

    private Access requireAccess(String firebaseUid, UUID projectId, ProjectPermission required) {
        UserId caller = requireActiveCaller(firebaseUid);
        ProjectId id = new ProjectId(projectId);

        Project project = projects.findById(id)
                                  .filter(candidate -> !candidate.isDeleted())
                                  .orElseThrow(() -> ProjectNotFoundException.byId(id));

        return new Access(project, caller, project.requireAccess(caller, required));
    }

    private UserId requireActiveCaller(String firebaseUid) {
        FirebaseUid uid = new FirebaseUid(firebaseUid);
        return activeUsers.findActiveUser(uid)
                          .orElseThrow(() -> UserNotFoundException.byFirebaseUid(uid));
    }

    private void saveAndPublish(Project project) {
        projects.save(project);
        eventPublisher.publishAll(project.pullDomainEvents());
    }

    private ProjectView viewOf(UserId caller, ProjectId projectId) {
        return projectFinder.detailFor(caller, projectId)
                            .orElseThrow(() -> ProjectNotFoundException.byId(projectId));
    }

    private record Access(Project project, UserId caller, ProjectPermission permission) {
    }

    public record CreateProjectCommand(String firebaseUid, String name) {
    }

    public record ProjectCommand(String firebaseUid, UUID projectId) {
    }

    public record ProjectQuery(String firebaseUid, UUID projectId) {
    }

    public record RenameProjectCommand(String firebaseUid, UUID projectId, String name) {
    }

    public record GrantAccessCommand(String firebaseUid,
            UUID projectId,
            UUID userId,
            ProjectPermission permission) {
    }

    public record ChangeAccessCommand(String firebaseUid,
            UUID projectId,
            UUID userId,
            ProjectPermission permission) {
    }

    public record RevokeAccessCommand(String firebaseUid, UUID projectId, UUID userId) {
    }

    public record ListProjectsCommand(String firebaseUid, ProjectListCriteria criteria) {
    }

    public record ListProjectMembersQuery(String firebaseUid,
            UUID projectId,
            ProjectMemberListCriteria criteria) {
    }
}
