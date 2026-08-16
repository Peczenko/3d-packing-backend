package com.packing.backend.core.user;

import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.core.user.port.in.LoadUserAuthorizationUseCase;
import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserNotFoundException;
import com.packing.backend.domain.user.Username;
import com.packing.backend.domain.user.UsernameAlreadyTakenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class UserApplicationService implements LoadUserAuthorizationUseCase {

    private static final int MAX_USERNAME_SUFFIX_ATTEMPTS = 20;

    private final UserRepository       users;
    private final DomainEventPublisher eventPublisher;
    private final Clock                clock;

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAuthorization> loadAuthorization(String firebaseUid) {
        return users.findByFirebaseUid(new FirebaseUid(firebaseUid))
                    .map(user -> new UserAuthorization(user.id()
                                                           .value(),
                                                       user.role(),
                                                       user.status()));
    }

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
        if (!user.isActive()) {
            throw new DomainRuleViolationException(
                                                   "Cannot sign in as a " + user.status()
                                                                                .name()
                                                                                .toLowerCase()
                                                           + " user");
        }
        user.changeEmail(email, now);
        user.recordLogin(now);
        users.recordSignIn(user.id(), user.email(), user.updatedAt(), now);
        return UserView.from(user);
    }

    public UserView updateProfile(UpdateUserProfileCommand command) {
        Instant now = clock.instant();
        User user = requireByFirebaseUid(new FirebaseUid(command.firebaseUid()));

        Username newUsername = new Username(command.username());
        if (!user.username()
                 .equals(newUsername)
                && users.existsByUsername(newUsername)) {
            throw new UsernameAlreadyTakenException(newUsername);
        }

        user.changeProfile(newUsername, command.displayName(), now);
        return saveAndPublish(user);
    }

    public void deleteAccount(String firebaseUid) {
        Instant now = clock.instant();
        User user = requireByFirebaseUid(new FirebaseUid(firebaseUid));
        user.delete(now);
        saveAndPublish(user);
    }

    public UserView assignRole(AssignUserRoleCommand command) {
        Instant now = clock.instant();
        UserId userId = new UserId(command.userId());
        User user = users.findById(userId)
                         .orElseThrow(() -> UserNotFoundException.byId(userId));

        user.assignRole(command.role(), now);
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
        Username fromUid = Username.suggestionFrom(firebaseUid.value());
        if (users.existsByUsername(fromUid)) {
            throw new UsernameAlreadyTakenException(fromUid);
        }
        return fromUid;
    }

    private Username deriveUsernameStem(FirebaseUid firebaseUid, Email email, String displayName) {
        for (String candidate : new String[] { email.localPart(), displayName, firebaseUid.value() }) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                return Username.suggestionFrom(candidate);
            } catch (RuntimeException ignored) {
            }
        }
        throw new IllegalStateException(
                                        "Could not derive a username for Firebase uid " + firebaseUid);
    }

    public record ResolveCurrentUserCommand(String firebaseUid, String email, String displayName) {
    }

    public record UpdateUserProfileCommand(String firebaseUid, String username, String displayName) {
    }

    public record AssignUserRoleCommand(UUID userId, UserRole role) {
    }
}
