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

/**
 * A shared workspace. Owns its files and its membership.
 *
 * <p>Members live inside the aggregate rather than beside it because the "at least one
 * owner" rule spans them: two concurrent demotions each look legal in isolation, and only a
 * single consistency boundary — here, guarded by {@link #version()} — can reject the second.
 *
 * <p>{@code createdBy} is audit data with no authorisation meaning. The creator is simply
 * the first {@code OWNER} and can be demoted or removed like anyone else, provided another
 * owner remains.
 */
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public final class Project extends AggregateRoot {

    public static final long INITIAL_VERSION = 0L;

    @EqualsAndHashCode.Include
    private final ProjectId id;

    private final UserId createdBy;
    private final Instant createdAt;

    /**
     * Insertion-ordered so that listing members is stable across reads. The generated
     * accessor is suppressed: {@link #members()} below hands out an immutable copy, and
     * leaking the live map would let a caller edit membership around the invariants.
     */
    @Getter(AccessLevel.NONE)
    private final Map<UserId, ProjectMember> members = new LinkedHashMap<>();

    private ProjectName name;
    private ProjectStatus status;
    private long version;
    private Instant updatedAt;
    private Instant deletedAt;

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

    /** Only the persistence adapter should call this. */
    public static Project rehydrate(ProjectId id,
                                    ProjectName name,
                                    UserId createdBy,
                                    ProjectStatus status,
                                    long version,
                                    Instant createdAt,
                                    Instant updatedAt,
                                    Instant deletedAt,
                                    Collection<ProjectMember> members) {
        return new Project(id, name, createdBy, status, version, createdAt, updatedAt,
                deletedAt, members);
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

    /** Idempotent. The caller cascades the deletion to the project's files. */
    public void delete(Instant now) {
        if (isDeleted()) {
            return;
        }
        this.status = ProjectStatus.DELETED;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    /**
     * Adds a member, or re-levels one who is already there. Records
     * {@link ProjectAccessGranted} only in the first case — the event exists to welcome a
     * new member, and an existing one has already been welcomed.
     */
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

    /** Removing someone who is not a member is a no-op, so revoking twice is safe. */
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
        return Optional.ofNullable(members.get(user)).map(ProjectMember::permission);
    }

    /**
     * The authorisation gate for every operation on this project.
     *
     * <p>A non-member gets the same 404 as a project that does not exist: distinguishing the
     * two would confirm an id, turning every route into an enumeration oracle. Once
     * membership is established the id is no longer a secret, so an insufficient level can
     * say so plainly.
     */
    public ProjectPermission requireAccess(UserId caller, ProjectPermission required) {
        ProjectPermission actual = permissionOf(caller)
                .orElseThrow(() -> ProjectNotFoundException.byId(id));
        if (!actual.allows(required)) {
            throw new PermissionDeniedException(
                    "This action requires " + required + " permission on project " + id);
        }
        return actual;
    }

    /** Every mutation funnels through here, which is what makes DISABLED read-only. */
    public void requireWritable() {
        if (status != ProjectStatus.ACTIVE) {
            throw new ResourceConflictException(
                    "Project " + id + " is " + status.name().toLowerCase()
                            + " and cannot be modified");
        }
    }

    public List<ProjectMember> members() {
        return List.copyOf(members.values());
    }

    public boolean isDeleted() {
        return status == ProjectStatus.DELETED;
    }

    /**
     * Bumps the version after a successful save, so a second save in the same unit of work
     * is not rejected as stale.
     */
    public void markPersisted() {
        this.version++;
    }

    /**
     * Checked <em>before</em> the mutation, not after. Validating afterwards would leave the
     * aggregate holding the rejected state: the caller sees the exception and abandons the
     * write, but anything still referencing this instance would read a project with no owner.
     */
    private void ensureNotTheLastOwner(UserId user) {
        ProjectMember member = members.get(user);
        if (member == null || !member.isOwner()) {
            return;
        }
        boolean anotherOwnerRemains = members.values().stream()
                .anyMatch(other -> other.isOwner() && !other.userId().equals(user));
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
