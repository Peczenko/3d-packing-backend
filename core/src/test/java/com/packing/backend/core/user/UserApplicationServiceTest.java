package com.packing.backend.core.user;

import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.core.user.port.in.AssignUserRoleUseCase.AssignUserRoleCommand;
import com.packing.backend.core.user.port.in.ResolveCurrentUserUseCase.ResolveCurrentUserCommand;
import com.packing.backend.core.user.port.in.UpdateUserProfileUseCase.UpdateUserProfileCommand;
import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.shared.DomainEvent;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserNotFoundException;
import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;
import com.packing.backend.domain.user.Username;
import com.packing.backend.domain.user.UsernameAlreadyTakenException;
import com.packing.backend.domain.user.event.UserAccountDeleted;
import com.packing.backend.domain.user.event.UserRegistered;
import com.packing.backend.domain.user.event.UserRoleChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-19T10:15:30Z");
    private static final String UID = "firebase-uid-1";

    @Mock
    private UserRepository users;
    @Mock
    private DomainEventPublisher eventPublisher;

    private UserApplicationService service;

    @BeforeEach
    void setUp() {
        service = new UserApplicationService(
                users, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private User existingUser() {
        return userWithStatus(UserStatus.ACTIVE);
    }

    private User userWithStatus(UserStatus status) {
        return User.rehydrate(
                UserId.generate(),
                new FirebaseUid(UID),
                new Email("ada@example.com"),
                new Username("ada"),
                "Ada Lovelace",
                UserRole.USER,
                status,
                3L,
                NOW.minusSeconds(86400),
                NOW.minusSeconds(86400),
                null);
    }

    @SuppressWarnings("unchecked")
    private Collection<? extends DomainEvent> publishedEvents() {
        ArgumentCaptor<Collection<? extends DomainEvent>> captor =
                ArgumentCaptor.forClass(Collection.class);
        verify(eventPublisher).publishAll(captor.capture());
        return captor.getValue();
    }

    private void repositoryEchoesSaves() {
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // --- just-in-time provisioning -------------------------------------------------

    @Test
    void provisionsANewUserOnFirstSight() {
        when(users.findByFirebaseUid(new FirebaseUid(UID))).thenReturn(Optional.empty());
        when(users.existsByUsername(any(Username.class))).thenReturn(false);
        repositoryEchoesSaves();

        UserView view = service.resolveCurrentUser(
                new ResolveCurrentUserCommand(UID, "Ada@Example.com", "Ada Lovelace"));

        assertThat(view.firebaseUid()).isEqualTo(UID);
        assertThat(view.email()).isEqualTo("ada@example.com");
        assertThat(view.role()).isEqualTo(UserRole.USER);
        // Seeded from the email local part.
        assertThat(view.username()).isEqualTo("ada");
        assertThat(view.lastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void publishesTheRegistrationEventForANewUser() {
        when(users.findByFirebaseUid(any(FirebaseUid.class))).thenReturn(Optional.empty());
        when(users.existsByUsername(any(Username.class))).thenReturn(false);
        repositoryEchoesSaves();

        service.resolveCurrentUser(new ResolveCurrentUserCommand(UID, "ada@example.com", null));

        assertThat(publishedEvents()).singleElement().isInstanceOf(UserRegistered.class);
    }

    @Test
    void appendsASuffixWhenTheDerivedUsernameIsTaken() {
        when(users.findByFirebaseUid(any(FirebaseUid.class))).thenReturn(Optional.empty());
        when(users.existsByUsername(new Username("ada"))).thenReturn(true);
        when(users.existsByUsername(new Username("ada2"))).thenReturn(false);
        repositoryEchoesSaves();

        UserView view = service.resolveCurrentUser(
                new ResolveCurrentUserCommand(UID, "ada@example.com", null));

        assertThat(view.username()).isEqualTo("ada2");
    }

    @Test
    void reusesTheExistingProfileAndRefreshesTheEmailFromTheToken() {
        User existing = existingUser();
        when(users.findByFirebaseUid(new FirebaseUid(UID))).thenReturn(Optional.of(existing));

        UserView view = service.resolveCurrentUser(
                new ResolveCurrentUserCommand(UID, "ada@newdomain.com", "Ada Lovelace"));

        assertThat(view.id()).isEqualTo(existing.id().value());
        assertThat(view.email()).isEqualTo("ada@newdomain.com");
        assertThat(view.lastLoginAt()).isEqualTo(NOW);
        verify(users, never()).existsByUsername(any());
    }

    /**
     * The sign-in path must never write the whole aggregate: doing so would carry role,
     * status and username from a possibly stale read and revert a concurrent change.
     */
    @Test
    void signingInUsesTheNarrowWriteRatherThanAFullAggregateSave() {
        User existing = existingUser();
        when(users.findByFirebaseUid(new FirebaseUid(UID))).thenReturn(Optional.of(existing));

        service.resolveCurrentUser(
                new ResolveCurrentUserCommand(UID, "ada@newdomain.com", "Ada Lovelace"));

        verify(users).recordSignIn(eq(existing.id()), eq(new Email("ada@newdomain.com")),
                any(Instant.class), eq(NOW));
        verify(users, never()).save(any());
    }

    @Test
    void aDisabledUserCannotSignIn() {
        when(users.findByFirebaseUid(new FirebaseUid(UID)))
                .thenReturn(Optional.of(userWithStatus(UserStatus.DISABLED)));

        assertThatThrownBy(() -> service.resolveCurrentUser(
                new ResolveCurrentUserCommand(UID, "ada@example.com", null)))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    /**
     * The tombstone is the whole point of soft deletion: an ID token issued before the
     * account was deleted stays valid for up to an hour, and must not be able to
     * re-provision a fresh profile.
     */
    @Test
    void aDeletedUserCannotSignInAndIsNotReProvisioned() {
        when(users.findByFirebaseUid(new FirebaseUid(UID)))
                .thenReturn(Optional.of(userWithStatus(UserStatus.DELETED)));

        assertThatThrownBy(() -> service.resolveCurrentUser(
                new ResolveCurrentUserCommand(UID, "ada@example.com", null)))
                .isInstanceOf(DomainRuleViolationException.class);

        verify(users, never()).save(any());
    }

    // --- authorization lookup ------------------------------------------------------

    @Test
    void authorizationIsReadFromTheStoredRoleNotFromAnyTokenClaim() {
        User admin = existingUser();
        admin.assignRole(UserRole.ADMIN, NOW);
        when(users.findByFirebaseUid(new FirebaseUid(UID))).thenReturn(Optional.of(admin));

        assertThat(service.loadAuthorization(UID)).hasValueSatisfying(authorization -> {
            assertThat(authorization.role()).isEqualTo(UserRole.ADMIN);
            assertThat(authorization.isActive()).isTrue();
        });
    }

    @Test
    void authorizationIsEmptyForAnIdentityWithNoProfileYet() {
        when(users.findByFirebaseUid(any(FirebaseUid.class))).thenReturn(Optional.empty());

        assertThat(service.loadAuthorization(UID)).isEmpty();
    }

    @Test
    void authorizationReportsATombstoneAsInactive() {
        when(users.findByFirebaseUid(new FirebaseUid(UID)))
                .thenReturn(Optional.of(userWithStatus(UserStatus.DELETED)));

        assertThat(service.loadAuthorization(UID))
                .hasValueSatisfying(authorization -> assertThat(authorization.isActive()).isFalse());
    }

    // --- profile updates -----------------------------------------------------------

    @Test
    void rejectsAProfileUpdateThatTakesAnotherUsersUsername() {
        when(users.findByFirebaseUid(new FirebaseUid(UID))).thenReturn(Optional.of(existingUser()));
        when(users.existsByUsername(new Username("taken"))).thenReturn(true);

        assertThatThrownBy(() -> service.updateProfile(
                new UpdateUserProfileCommand(UID, "taken", null)))
                .isInstanceOf(UsernameAlreadyTakenException.class);

        verify(users, never()).save(any());
    }

    @Test
    void keepingTheSameUsernameDoesNotTripTheUniquenessCheck() {
        when(users.findByFirebaseUid(new FirebaseUid(UID))).thenReturn(Optional.of(existingUser()));
        repositoryEchoesSaves();

        UserView view = service.updateProfile(new UpdateUserProfileCommand(UID, "ada", "New Name"));

        assertThat(view.displayName()).isEqualTo("New Name");
        verify(users, never()).existsByUsername(any());
    }

    @Test
    void updatingAnUnknownUserFails() {
        when(users.findByFirebaseUid(any(FirebaseUid.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProfile(
                new UpdateUserProfileCommand(UID, "ada", null)))
                .isInstanceOf(UserNotFoundException.class);
    }

    // --- deletion ------------------------------------------------------------------

    @Test
    void deletingAnonymisesTheProfileAndLeavesATombstone() {
        User existing = existingUser();
        when(users.findByFirebaseUid(new FirebaseUid(UID))).thenReturn(Optional.of(existing));
        repositoryEchoesSaves();

        service.deleteAccount(UID);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(UserStatus.DELETED);
        assertThat(saved.getValue().email().value()).doesNotContain("ada@example.com");
        assertThat(saved.getValue().displayName()).isNull();
        // Retained: it is the key the authorization lookup rejects the token on.
        assertThat(saved.getValue().firebaseUid()).isEqualTo(existing.firebaseUid());
    }

    @Test
    void deletingPublishesTheEventThatRemovesTheFirebaseIdentityAfterCommit() {
        when(users.findByFirebaseUid(new FirebaseUid(UID))).thenReturn(Optional.of(existingUser()));
        repositoryEchoesSaves();

        service.deleteAccount(UID);

        assertThat(publishedEvents()).singleElement().isInstanceOf(UserAccountDeleted.class);
    }

    @Test
    void deletingAnUnknownUserFails() {
        when(users.findByFirebaseUid(any(FirebaseUid.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteAccount(UID))
                .isInstanceOf(UserNotFoundException.class);
    }

    // --- role assignment -----------------------------------------------------------

    /**
     * Firebase must not be called inside the transaction: a rollback cannot undo a granted
     * ADMIN claim. The mirroring is driven by this event after commit instead.
     */
    @Test
    void assigningARolePublishesTheEventAndTouchesNoExternalSystem() {
        User existing = existingUser();
        when(users.findById(existing.id())).thenReturn(Optional.of(existing));
        repositoryEchoesSaves();

        UserView view = service.assignRole(
                new AssignUserRoleCommand(existing.id().value(), UserRole.ADMIN));

        assertThat(view.role()).isEqualTo(UserRole.ADMIN);
        assertThat(publishedEvents()).singleElement()
                .isInstanceOfSatisfying(UserRoleChanged.class, event -> {
                    assertThat(event.newRole()).isEqualTo(UserRole.ADMIN);
                    assertThat(event.firebaseUid()).isEqualTo(existing.firebaseUid());
                });
    }

    @Test
    void assigningARoleToAnUnknownUserFails() {
        UserId missing = UserId.generate();
        when(users.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignRole(
                new AssignUserRoleCommand(missing.value(), UserRole.ADMIN)))
                .isInstanceOf(UserNotFoundException.class);
    }
}
