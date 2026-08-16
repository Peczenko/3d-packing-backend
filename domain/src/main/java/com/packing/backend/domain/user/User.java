package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.AggregateRoot;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.event.UserAccountDeleted;
import com.packing.backend.domain.user.event.UserProfileChanged;
import com.packing.backend.domain.user.event.UserRegistered;
import com.packing.backend.domain.user.event.UserRoleChanged;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public final class User extends AggregateRoot {

    public static final int MAX_DISPLAY_NAME_LENGTH = 128;

    public static final long INITIAL_VERSION = 0L;

    private static final String TOMBSTONE_EMAIL_DOMAIN = "@deleted.invalid";
    private static final String TOMBSTONE_PREFIX       = "deleted-";

    @EqualsAndHashCode.Include
    private final UserId id;

    private final FirebaseUid firebaseUid;
    private final Instant     createdAt;

    private Email    email;
    private Username username;

    private String displayName;

    private UserRole   role;
    private UserStatus status;
    private long       version;
    private Instant    updatedAt;
    private Instant    lastLoginAt;

    private User(UserId id,
                 FirebaseUid firebaseUid,
                 Email email,
                 Username username,
                 String displayName,
                 UserRole role,
                 UserStatus status,
                 long version,
                 Instant createdAt,
                 Instant updatedAt,
                 Instant lastLoginAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.firebaseUid = Objects.requireNonNull(firebaseUid, "firebaseUid");
        this.email = Objects.requireNonNull(email, "email");
        this.username = Objects.requireNonNull(username, "username");
        this.displayName = normaliseDisplayName(displayName);
        this.role = Objects.requireNonNull(role, "role");
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.lastLoginAt = lastLoginAt;
    }

    public static User register(FirebaseUid firebaseUid,
                                Email email,
                                Username username,
                                String displayName,
                                Instant now) {
        User user = new User(
                             UserId.generate(),
                             firebaseUid,
                             email,
                             username,
                             displayName,
                             UserRole.USER,
                             UserStatus.ACTIVE,
                             INITIAL_VERSION,
                             now,
                             now,
                             null);
        user.recordEvent(new UserRegistered(user.id, user.firebaseUid, user.email, now));
        return user;
    }

    public static User rehydrate(UserId id,
                                 FirebaseUid firebaseUid,
                                 Email email,
                                 Username username,
                                 String displayName,
                                 UserRole role,
                                 UserStatus status,
                                 long version,
                                 Instant createdAt,
                                 Instant updatedAt,
                                 Instant lastLoginAt) {
        return new User(id,
                        firebaseUid,
                        email,
                        username,
                        displayName,
                        role,
                        status,
                        version,
                        createdAt,
                        updatedAt,
                        lastLoginAt);
    }

    public void changeProfile(Username newUsername, String newDisplayName, Instant now) {
        ensureActive("change the profile of");
        String normalised = normaliseDisplayName(newDisplayName);
        if (this.username.equals(newUsername) && Objects.equals(this.displayName, normalised)) {
            return;
        }
        this.username = Objects.requireNonNull(newUsername, "username");
        this.displayName = normalised;
        this.updatedAt = now;
        recordEvent(new UserProfileChanged(id, this.username, this.displayName, now));
    }

    public void changeEmail(Email newEmail, Instant now) {
        ensureNotDeleted("change the email of");
        if (this.email.equals(newEmail)) {
            return;
        }
        this.email = Objects.requireNonNull(newEmail, "email");
        this.updatedAt = now;
    }

    public void assignRole(UserRole newRole, Instant now) {
        Objects.requireNonNull(newRole, "role");
        ensureNotDeleted("assign a role to");
        if (this.role == newRole) {
            return;
        }
        UserRole previous = this.role;
        this.role = newRole;
        this.updatedAt = now;
        recordEvent(new UserRoleChanged(id, firebaseUid, previous, newRole, now));
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    public void disable(Instant now) {
        ensureNotDeleted("disable");
        if (this.status == UserStatus.DISABLED) {
            return;
        }
        this.status = UserStatus.DISABLED;
        this.updatedAt = now;
    }

    public void enable(Instant now) {
        ensureNotDeleted("enable");
        if (this.status == UserStatus.ACTIVE) {
            return;
        }
        this.status = UserStatus.ACTIVE;
        this.updatedAt = now;
    }

    public void delete(Instant now) {
        if (this.status == UserStatus.DELETED) {
            return;
        }
        this.email = new Email(TOMBSTONE_PREFIX + id.value() + TOMBSTONE_EMAIL_DOMAIN);
        this.username = new Username(TOMBSTONE_PREFIX + id.value());
        this.displayName = null;
        this.status = UserStatus.DELETED;
        this.updatedAt = now;
        recordEvent(new UserAccountDeleted(id, firebaseUid, now));
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isDeleted() {
        return status == UserStatus.DELETED;
    }

    public void markPersisted() {
        this.version++;
    }

    private void ensureActive(String operation) {
        if (!isActive()) {
            throw new DomainRuleViolationException(
                                                   "Cannot " + operation + " a " + status.name()
                                                                                         .toLowerCase()
                                                           + " user: " + id);
        }
    }

    private void ensureNotDeleted(String operation) {
        if (isDeleted()) {
            throw new DomainRuleViolationException("Cannot " + operation + " a deleted user: " + id);
        }
    }

    private static String normaliseDisplayName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new DomainRuleViolationException(
                                                   "Display name must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    @Override
    public String toString() {
        return "User[id=" + id + ", username=" + username + ", role=" + role + ", status=" + status + "]";
    }
}
