package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.AggregateRoot;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.event.UserAccountDeleted;
import com.packing.backend.domain.user.event.UserProfileChanged;
import com.packing.backend.domain.user.event.UserRegistered;
import com.packing.backend.domain.user.event.UserRoleChanged;

import java.time.Instant;
import java.util.Objects;

/**
 * A user of the packing platform.
 *
 * <p>Firebase owns authentication: credentials, sign-in methods and email verification
 * never appear here. This aggregate owns the application-side profile and the
 * authorisation role, linked to the Firebase identity by {@link FirebaseUid}.
 *
 * <p>Both {@code id} and {@code firebaseUid} are immutable — re-pointing a profile at a
 * different identity is not a domain operation.
 *
 * <p>{@link #version()} is an optimistic lock. It is a persistence concern living on the
 * aggregate deliberately: without it, two requests that read the same row concurrently
 * would both write their full snapshot and the later one would silently revert the
 * other's change.
 */
public final class User extends AggregateRoot {

    public static final int MAX_DISPLAY_NAME_LENGTH = 128;

    /** Version of an aggregate that has never been written. */
    public static final long INITIAL_VERSION = 0L;

    /**
     * Reserved TLD (RFC 2606), so an anonymised address can never collide with, or be
     * mistaken for, a real one.
     */
    private static final String TOMBSTONE_EMAIL_DOMAIN = "@deleted.invalid";
    private static final String TOMBSTONE_PREFIX = "deleted-";

    private final UserId id;
    private final FirebaseUid firebaseUid;
    private final Instant createdAt;

    private Email email;
    private Username username;
    private String displayName;
    private UserRole role;
    private UserStatus status;
    private long version;
    private Instant updatedAt;
    private Instant lastLoginAt;

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

    /**
     * Creates the local profile for a Firebase identity that has just been seen for the
     * first time. This is the just-in-time provisioning entry point.
     */
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

    /**
     * Rebuilds an aggregate from stored state. Records no events — nothing has happened,
     * the user is merely being read back.
     *
     * <p>Only the persistence adapter should call this.
     */
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
        return new User(id, firebaseUid, email, username, displayName, role, status, version,
                createdAt, updatedAt, lastLoginAt);
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

    /**
     * Keeps the local email in step with Firebase, which is the system of record for it.
     * Permitted on a disabled user so that a re-enabled account is not left stale, but not
     * on a deleted one — that would undo the anonymisation.
     */
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

    /**
     * Deliberately does not touch {@code updatedAt}: that field means "profile last
     * changed", and a sign-in changes nothing about the profile.
     */
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

    /**
     * Anonymises the profile and marks it deleted, leaving a tombstone.
     *
     * <p>The row is kept rather than removed because a Firebase ID token issued before the
     * deletion stays valid for up to an hour. With no record of the deletion, that token
     * would pass authentication and the just-in-time provisioning path would hand its
     * bearer a brand new profile.
     *
     * <p>Everything identifying is replaced with an id-derived placeholder: the real email
     * and username are released for reuse, and only {@link FirebaseUid} — an opaque
     * identifier — is retained, because it is the key the rejection check looks up.
     */
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

    /**
     * Records that the current state has been written. Called by the persistence adapter
     * after a successful save so that a second save in the same unit of work carries the
     * version the database now holds.
     */
    public void markPersisted() {
        this.version++;
    }

    private void ensureActive(String operation) {
        if (!isActive()) {
            throw new DomainRuleViolationException(
                    "Cannot " + operation + " a " + status.name().toLowerCase() + " user: " + id);
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

    public UserId id() {
        return id;
    }

    public FirebaseUid firebaseUid() {
        return firebaseUid;
    }

    public Email email() {
        return email;
    }

    public Username username() {
        return username;
    }

    /** May be null: Firebase accounts are not required to have a display name. */
    public String displayName() {
        return displayName;
    }

    public UserRole role() {
        return role;
    }

    public UserStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** Null until the user's first recorded sign-in. */
    public Instant lastLoginAt() {
        return lastLoginAt;
    }

    /** Identity is the aggregate id; no other field participates. */
    @Override
    public boolean equals(Object other) {
        return other instanceof User user && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "User[id=" + id + ", username=" + username + ", role=" + role + ", status=" + status + "]";
    }
}
