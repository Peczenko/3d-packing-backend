package com.packing.backend.core.user;

import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.core.user.port.in.AssignUserRoleUseCase;
import com.packing.backend.core.user.port.in.DeleteUserAccountUseCase;
import com.packing.backend.core.user.port.in.LoadUserAuthorizationUseCase;
import com.packing.backend.core.user.port.in.ResolveCurrentUserUseCase;
import com.packing.backend.core.user.port.in.UpdateUserProfileUseCase;
import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserNotFoundException;
import com.packing.backend.domain.user.Username;
import com.packing.backend.domain.user.UsernameAlreadyTakenException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestrates the user use cases. Business rules live in {@link User}; this class only
 * sequences repository calls and event publication.
 *
 * <p>It deliberately makes <em>no</em> calls to Firebase. Anything that mutates
 * Firebase-side state is driven by a domain event handled after the database commits, so
 * that a rollback can never leave PostgreSQL and Firebase disagreeing about a role — the
 * two systems cannot participate in one transaction, and a failed commit must not leave
 * an elevated claim behind.
 *
 * <p>This is a plain class — no {@code @Service}, no {@code @Component}. It is registered
 * as a bean from {@code :app}. The single Spring import is {@code @Transactional}, a
 * documented exception recorded in {@code CLAUDE.md}.
 */
@Transactional
public class UserApplicationService implements
        ResolveCurrentUserUseCase,
        UpdateUserProfileUseCase,
        DeleteUserAccountUseCase,
        AssignUserRoleUseCase,
        LoadUserAuthorizationUseCase {

    /**
     * How many sequential suffixes to try before giving up on a readable username. Past
     * this point the derived stem is clearly contested and a uid-derived name is better
     * than {@code jsmith47}.
     */
    private static final int MAX_USERNAME_SUFFIX_ATTEMPTS = 20;

    private final UserRepository users;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock;

    public UserApplicationService(UserRepository users,
                                  DomainEventPublisher eventPublisher,
                                  Clock clock) {
        this.users = Objects.requireNonNull(users, "users");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAuthorization> loadAuthorization(String firebaseUid) {
        return users.findByFirebaseUid(new FirebaseUid(firebaseUid))
                .map(user -> new UserAuthorization(user.id().value(), user.role(), user.status()));
    }

    /**
     * Just-in-time provisioning. Firebase has already authenticated the caller, so a
     * missing local profile means "first visit", not "unauthorised".
     *
     * <p>The email is refreshed on every call because Firebase, not this system, owns it.
     */
    @Override
    public UserView resolveCurrentUser(ResolveCurrentUserCommand command) {
        Instant now = clock.instant();
        FirebaseUid firebaseUid = new FirebaseUid(command.firebaseUid());
        Email email = new Email(command.email());

        Optional<User> existing = users.findByFirebaseUid(firebaseUid);
        if (existing.isEmpty()) {
            User created = User.register(
                    firebaseUid,
                    email,
                    allocateUsername(firebaseUid, email, command.displayName()),
                    command.displayName(),
                    now);
            created.recordLogin(now);
            return saveAndPublish(created);
        }

        User user = existing.get();
        // Defence in depth: the security layer already rejects non-active identities, so
        // reaching here means the two checks have drifted apart.
        if (!user.isActive()) {
            throw new DomainRuleViolationException(
                    "Cannot sign in as a " + user.status().name().toLowerCase() + " user");
        }
        user.changeEmail(email, now);
        user.recordLogin(now);
        // Narrow write rather than save(): see UserRepository#recordSignIn.
        users.recordSignIn(user.id(), user.email(), user.updatedAt(), now);
        return UserView.from(user);
    }

    @Override
    public UserView updateProfile(UpdateUserProfileCommand command) {
        Instant now = clock.instant();
        User user = requireByFirebaseUid(new FirebaseUid(command.firebaseUid()));

        Username newUsername = new Username(command.username());
        // Checked here rather than relying solely on the unique constraint so that the
        // caller gets a precise error instead of a generic conflict. The constraint is
        // still the authority under concurrency.
        if (!user.username().equals(newUsername) && users.existsByUsername(newUsername)) {
            throw new UsernameAlreadyTakenException(newUsername);
        }

        user.changeProfile(newUsername, command.displayName(), now);
        return saveAndPublish(user);
    }

    /**
     * Anonymises the profile and marks it deleted. The Firebase identity is removed by the
     * handler for {@code UserAccountDeleted} once this transaction commits.
     *
     * <p>The tombstone is what makes the deletion effective immediately: an ID token
     * issued before it stays cryptographically valid for up to an hour, and the
     * authorisation lookup rejects it on the strength of this row.
     */
    @Override
    public void deleteAccount(String firebaseUid) {
        Instant now = clock.instant();
        User user = requireByFirebaseUid(new FirebaseUid(firebaseUid));
        user.delete(now);
        saveAndPublish(user);
    }

    @Override
    public UserView assignRole(AssignUserRoleCommand command) {
        Instant now = clock.instant();
        UserId userId = new UserId(command.userId());
        User user = users.findById(userId).orElseThrow(() -> UserNotFoundException.byId(userId));

        user.assignRole(command.role(), now);
        // The Firebase custom claim is mirrored after commit by the UserRoleChanged
        // handler. Authorisation reads the database, so the claim lagging is harmless.
        return saveAndPublish(user);
    }

    private UserView saveAndPublish(User user) {
        User saved = users.save(user);
        eventPublisher.publishAll(saved.pullDomainEvents());
        return UserView.from(saved);
    }

    private User requireByFirebaseUid(FirebaseUid firebaseUid) {
        return users.findByFirebaseUid(firebaseUid)
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> UserNotFoundException.byFirebaseUid(firebaseUid));
    }

    /**
     * Picks a free username for a user who never chose one.
     *
     * <p>Concurrent provisioning of the same stem can still race past the availability
     * checks; the unique constraint on {@code users.username} is the real guard and
     * surfaces as a conflict the client can retry.
     */
    private Username allocateUsername(FirebaseUid firebaseUid, Email email, String displayName) {
        Username base = deriveUsernameStem(firebaseUid, email, displayName);
        if (!users.existsByUsername(base)) {
            return base;
        }
        for (int suffix = 2; suffix <= MAX_USERNAME_SUFFIX_ATTEMPTS; suffix++) {
            Username candidate = base.withSuffix(suffix);
            if (!users.existsByUsername(candidate)) {
                return candidate;
            }
        }
        // The Firebase uid is unique by construction, so this terminates the search.
        Username fromUid = Username.suggestionFrom(firebaseUid.value());
        if (users.existsByUsername(fromUid)) {
            throw new UsernameAlreadyTakenException(fromUid);
        }
        return fromUid;
    }

    private Username deriveUsernameStem(FirebaseUid firebaseUid, Email email, String displayName) {
        for (String candidate : new String[]{email.localPart(), displayName, firebaseUid.value()}) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                return Username.suggestionFrom(candidate);
            } catch (RuntimeException ignored) {
                // Nothing salvageable in this candidate (e.g. an all-CJK display name);
                // fall through to the next one.
            }
        }
        throw new IllegalStateException(
                "Could not derive a username for Firebase uid " + firebaseUid);
    }
}
