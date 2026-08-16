package com.packing.backend.domain.project;

import com.packing.backend.domain.project.event.ProjectAccessGranted;
import com.packing.backend.domain.shared.AggregateRoot;
import com.packing.backend.domain.shared.PermissionDeniedException;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.user.UserId;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public final class Project extends AggregateRoot {

    public static final long INITIAL_VERSION = 0L;

    @EqualsAndHashCode.Include
    private final ProjectId id;

    private final UserId  createdBy;
    private final Instant createdAt;

    @Getter(AccessLevel.NONE)
    private final Map<UserId, ProjectMember> members = new LinkedHashMap<>();

    private ProjectName   name;
    private ProjectStatus status;
    private long          version;
    private Instant       updatedAt;
    private Instant       deletedAt;

    private Project(ProjectId id,
                    ProjectName name,
                    UserId createdBy,
                    ProjectStatus status,
                    long version,
                    Instant createdAt,
                    Instant updatedAt,
                    Instant deletedAt,
                    Collection<ProjectMember> members) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.deletedAt = deletedAt;
        members.forEach(member -> this.members.put(member.userId(), member));
    }

    public static Project create(ProjectName name, UserId creator, Instant now) {
        Objects.requireNonNull(creator, "creator");
        return new Project(
                           ProjectId.generate(),
                           name,
                           creator,
                           ProjectStatus.ACTIVE,
                           INITIAL_VERSION,
                           now,
                           now,
                           null,
                           List.of(new ProjectMember(creator, ProjectPermission.OWNER, creator, now)));
    }

    public static Project rehydrate(ProjectId id,
                                    ProjectName name,
                                    UserId createdBy,
                                    ProjectStatus status,
                                    long version,
                                    Instant createdAt,
                                    Instant updatedAt,
                                    Instant deletedAt,
                                    Collection<ProjectMember> members) {
        return new Project(id,
                           name,
                           createdBy,
                           status,
                           version,
                           createdAt,
                           updatedAt,
                           deletedAt,
                           members);
    }

    public void rename(ProjectName newName, Instant now) {
        requireWritable();
        Objects.requireNonNull(newName, "name");
        if (this.name.equals(newName)) {
            return;
        }
        this.name = newName;
        this.updatedAt = now;
    }

    public void disable(Instant now) {
        ensureNotDeleted("disable");
        if (this.status == ProjectStatus.DISABLED) {
            return;
        }
        this.status = ProjectStatus.DISABLED;
        this.updatedAt = now;
    }

    public void activate(Instant now) {
        ensureNotDeleted("activate");
        if (this.status == ProjectStatus.ACTIVE) {
            return;
        }
        this.status = ProjectStatus.ACTIVE;
        this.updatedAt = now;
    }

    public void delete(Instant now) {
        if (isDeleted()) {
            return;
        }
        this.status = ProjectStatus.DELETED;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public void grantAccess(UserId user,
                            ProjectPermission permission,
                            UserId grantedBy,
                            Instant now) {
        requireWritable();
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(permission, "permission");

        ProjectMember existing = members.get(user);
        if (existing == null) {
            members.put(user, new ProjectMember(user, permission, grantedBy, now));
            this.updatedAt = now;
            recordEvent(new ProjectAccessGranted(id, name, user, permission, grantedBy, now));
            return;
        }
        if (existing.permission() == permission) {
            return;
        }
        if (permission != ProjectPermission.OWNER) {
            ensureNotTheLastOwner(user);
        }
        members.put(user, existing.withPermission(permission));
        this.updatedAt = now;
    }

    public void revokeAccess(UserId user, Instant now) {
        requireWritable();
        if (!members.containsKey(user)) {
            return;
        }
        ensureNotTheLastOwner(user);
        members.remove(user);
        this.updatedAt = now;
    }

    public Optional<ProjectPermission> permissionOf(UserId user) {
        return Optional.ofNullable(members.get(user))
                       .map(ProjectMember::permission);
    }

    public ProjectPermission requireAccess(UserId caller, ProjectPermission required) {
        ProjectPermission actual = permissionOf(caller)
                                                       .orElseThrow(() -> ProjectNotFoundException.byId(id));
        if (!actual.allows(required)) {
            throw new PermissionDeniedException(
                                                "This action requires " + required + " permission on project " + id);
        }
        return actual;
    }

    public void requireWritable() {
        if (status != ProjectStatus.ACTIVE) {
            throw new ResourceConflictException(
                                                "Project " + id + " is " + status.name()
                                                                                 .toLowerCase()
                                                        + " and cannot be modified");
        }
    }

    public List<ProjectMember> members() {
        return List.copyOf(members.values());
    }

    public boolean isDeleted() {
        return status == ProjectStatus.DELETED;
    }

    public void markPersisted() {
        this.version++;
    }

    private void ensureNotTheLastOwner(UserId user) {
        ProjectMember member = members.get(user);
        if (member == null || !member.isOwner()) {
            return;
        }
        boolean anotherOwnerRemains = members.values()
                                             .stream()
                                             .anyMatch(other -> other.isOwner() && !other.userId()
                                                                                         .equals(user));
        if (!anotherOwnerRemains) {
            throw new ResourceConflictException(
                                                "Project " + id + " must keep at least one owner. Promote another member "
                                                        + "first.");
        }
    }

    private void ensureNotDeleted(String operation) {
        if (isDeleted()) {
            throw new ResourceConflictException(
                                                "Cannot " + operation + " a deleted project: " + id);
        }
    }

    @Override
    public String toString() {
        return "Project[id=" + id + ", name=" + name + ", status=" + status
                + ", members=" + members.size() + "]";
    }
}
