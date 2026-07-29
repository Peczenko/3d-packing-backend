package com.packing.backend.core.project;

import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.project.port.out.ProjectRepository;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.port.out.ActiveUserLookup;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectMember;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.project.ProjectNotFoundException;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.shared.PermissionDeniedException;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserNotFoundException;
import com.packing.backend.domain.user.Username;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ProjectApplicationService {

    private final ProjectRepository projects;
    private final UserRepository users;
    private final ActiveUserLookup activeUsers;
    private final FileRepository files;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock;

    public ProjectView createProject(CreateProjectCommand command) {
        Instant now = clock.instant();
        UserId caller = requireActiveCaller(command.firebaseUid());

        Project project = Project.create(new ProjectName(command.name()), caller, now);
        return viewOf(saveAndPublish(project), caller);
    }

    @Transactional(readOnly = true)
    public Page<ProjectSummaryView> listProjects(ListProjectsCommand command) {
        UserId caller = requireActiveCaller(command.firebaseUid());

        List<ProjectSummaryView> content = projects
                .findByMember(caller, (int) command.page().offset(), command.page().size())
                .stream()
                .map(project -> ProjectSummaryView.of(
                        project, project.requireAccess(caller, ProjectPermission.READ)))
                .toList();

        return new Page<>(content, command.page().page(), command.page().size(),
                projects.countByMember(caller));
    }

    @Transactional(readOnly = true)
    public ProjectView getProject(ProjectQuery query) {
        Access access = requireAccess(query.firebaseUid(), query.projectId(), ProjectPermission.READ);
        return viewOf(access.project(), access.caller());
    }

    public ProjectView renameProject(RenameProjectCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.OWNER);

        access.project().rename(new ProjectName(command.name()), now);
        return viewOf(saveAndPublish(access.project()), access.caller());
    }

    public void disableProject(ProjectCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.OWNER);

        access.project().disable(now);
        saveAndPublish(access.project());
    }

    public void activateProject(ProjectCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.OWNER);

        access.project().activate(now);
        saveAndPublish(access.project());
    }

    /**
     * Tombstones the project and every file in it. The blobs are reclaimed after commit by
     * the {@code FileDeleted} handler in the infrastructure layer, which is why each file is
     * deleted through the aggregate rather than by a bulk {@code UPDATE}: the events are the
     * only record of which blobs to remove.
     *
     * <p>This loads every file row of the project. Acceptable at the sizes involved, and the
     * batched write keeps it to one round trip; a project large enough for that to hurt
     * would need a paged sweep instead.
     */
    public void deleteProject(ProjectCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.OWNER);

        ProjectId projectId = new ProjectId(command.projectId());
        List<StoredFile> contents = files.findAllAvailableByProject(projectId);
        contents.forEach(file -> file.delete(now));

        access.project().delete(now);
        projects.save(access.project());

        List<DomainEvent> events = new ArrayList<>(access.project().pullDomainEvents());
        files.saveAll(contents).forEach(file -> events.addAll(file.pullDomainEvents()));
        eventPublisher.publishAll(events);
    }

    /**
     * Adds a member, or changes an existing member's level — the aggregate treats both as one
     * operation, so both routes land here.
     */
    public ProjectView grantAccess(GrantAccessCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.OWNER);

        User member = resolveMember(command.identifier());
        access.project().grantAccess(member.id(), command.permission(), access.caller(), now);
        return viewOf(saveAndPublish(access.project()), access.caller());
    }

    /**
     * Re-levels someone who is already a member, addressed by user id.
     *
     * <p>Separate from {@link #grantAccess} because it must not be able to add anybody: that
     * path resolves a person by email or username and announces itself by email, and letting
     * a bare id slip in here would be a way to join someone to a project silently.
     */
    public ProjectView changeAccess(ChangeAccessCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.OWNER);

        UserId target = new UserId(command.userId());
        if (access.project().permissionOf(target).isEmpty()) {
            throw new UserNotFoundException("No member of this project with id " + target);
        }

        access.project().grantAccess(target, command.permission(), access.caller(), now);
        return viewOf(saveAndPublish(access.project()), access.caller());
    }

    /**
     * Removing yourself is leaving, and needs no permission beyond membership. Removing
     * anyone else is an owner's prerogative. The last-owner rule blocks both.
     */
    public void revokeAccess(RevokeAccessCommand command) {
        Instant now = clock.instant();
        Access access = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.READ);

        UserId target = new UserId(command.userId());
        if (!target.equals(access.caller())
                && !access.permission().allows(ProjectPermission.OWNER)) {
            throw new PermissionDeniedException(
                    "This action requires OWNER permission on project " + command.projectId());
        }

        access.project().revokeAccess(target, now);
        saveAndPublish(access.project());
    }

    /**
     * The failure message is identical whether the identifier looked like an email, a
     * username, or neither. Naming which half matched would make this endpoint a way to test
     * whether a given address is registered.
     */
    private User resolveMember(String identifier) {
        return tryFindByEmail(identifier)
                .or(() -> tryFindByUsername(identifier))
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new UserNotFoundException(
                        "No user matches that identifier"));
    }

    private Optional<User> tryFindByEmail(String identifier) {
        try {
            return users.findByEmail(new Email(identifier));
        } catch (DomainRuleViolationException notAnEmail) {
            return Optional.empty();
        }
    }

    private Optional<User> tryFindByUsername(String identifier) {
        try {
            return users.findByUsername(new Username(identifier));
        } catch (DomainRuleViolationException notAUsername) {
            return Optional.empty();
        }
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

    private Project saveAndPublish(Project project) {
        Project saved = projects.save(project);
        eventPublisher.publishAll(saved.pullDomainEvents());
        return saved;
    }

    /** One query for every member's identity, rather than one per member. */
    private ProjectView viewOf(Project project, UserId caller) {
        List<ProjectMember> members = project.members();
        Set<UserId> ids = members.stream().map(ProjectMember::userId).collect(Collectors.toSet());
        Map<UserId, User> identities = users.findAllByIds(ids).stream()
                .collect(Collectors.toMap(User::id, Function.identity()));

        List<ProjectMemberView> memberViews = members.stream()
                .filter(member -> identities.containsKey(member.userId()))
                .map(member -> ProjectMemberView.of(member, identities.get(member.userId())))
                .sorted(Comparator.comparing(ProjectMemberView::addedAt))
                .toList();

        return ProjectView.of(project, project.requireAccess(caller, ProjectPermission.READ),
                memberViews);
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
                                     String identifier,
                                     ProjectPermission permission) {
    }

    public record ChangeAccessCommand(String firebaseUid,
                                      UUID projectId,
                                      UUID userId,
                                      ProjectPermission permission) {
    }

    public record RevokeAccessCommand(String firebaseUid, UUID projectId, UUID userId) {
    }

    public record ListProjectsCommand(String firebaseUid, PageRequest page) {
    }
}
