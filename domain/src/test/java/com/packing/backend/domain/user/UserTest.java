package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.event.UserAccountDeleted;
import com.packing.backend.domain.user.event.UserProfileChanged;
import com.packing.backend.domain.user.event.UserRegistered;
import com.packing.backend.domain.user.event.UserRoleChanged;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-07-19T10:15:30Z");
    private static final Instant LATER = NOW.plusSeconds(3600);

    private User activeUser() {
        return User.register(
                new FirebaseUid("firebase-uid-1"),
                new Email("ada@example.com"),
                new Username("ada"),
                "Ada Lovelace",
                NOW);
    }

    @Test
    void registerStartsActiveWithTheUserRoleAndRecordsAnEvent() {
        User user = activeUser();

        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.role()).isEqualTo(UserRole.USER);
        assertThat(user.createdAt()).isEqualTo(NOW);
        assertThat(user.updatedAt()).isEqualTo(NOW);
        assertThat(user.lastLoginAt()).isNull();
        assertThat(user.domainEvents()).singleElement().isInstanceOf(UserRegistered.class);
    }

    @Test
    void pullDomainEventsDrainsTheBuffer() {
        User user = activeUser();

        assertThat(user.pullDomainEvents()).hasSize(1);
        assertThat(user.pullDomainEvents()).isEmpty();
    }

    @Test
    void changeProfileUpdatesTheTimestampAndRecordsAnEvent() {
        User user = activeUser();
        user.pullDomainEvents();

        user.changeProfile(new Username("ada.l"), "Ada L.", LATER);

        assertThat(user.username()).isEqualTo(new Username("ada.l"));
        assertThat(user.displayName()).isEqualTo("Ada L.");
        assertThat(user.updatedAt()).isEqualTo(LATER);
        assertThat(user.domainEvents()).singleElement().isInstanceOf(UserProfileChanged.class);
    }

    @Test
    void changeProfileToIdenticalValuesIsANoOp() {
        User user = activeUser();
        user.pullDomainEvents();

        user.changeProfile(new Username("ada"), "Ada Lovelace", LATER);

        assertThat(user.updatedAt()).isEqualTo(NOW);
        assertThat(user.domainEvents()).isEmpty();
    }

    @Test
    void blankDisplayNameIsStoredAsNull() {
        User user = activeUser();

        user.changeProfile(new Username("ada"), "   ", LATER);

        assertThat(user.displayName()).isNull();
    }

    @Test
    void displayNameLongerThanTheLimitIsRejected() {
        User user = activeUser();
        String tooLong = "x".repeat(User.MAX_DISPLAY_NAME_LENGTH + 1);

        assertThatThrownBy(() -> user.changeProfile(new Username("ada"), tooLong, LATER))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void aDisabledUserCannotChangeTheirProfile() {
        User user = activeUser();
        user.disable(LATER);

        assertThatThrownBy(() -> user.changeProfile(new Username("ada.l"), null, LATER))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void assignRoleRecordsThePreviousAndNewRole() {
        User user = activeUser();
        user.pullDomainEvents();

        user.assignRole(UserRole.ADMIN, LATER);

        assertThat(user.role()).isEqualTo(UserRole.ADMIN);
        assertThat(user.domainEvents()).singleElement()
                .isInstanceOfSatisfying(UserRoleChanged.class, event -> {
                    assertThat(event.previousRole()).isEqualTo(UserRole.USER);
                    assertThat(event.newRole()).isEqualTo(UserRole.ADMIN);
                    assertThat(event.firebaseUid()).isEqualTo(user.firebaseUid());
                });
    }

    @Test
    void assigningTheSameRoleAgainIsANoOp() {
        User user = activeUser();
        user.pullDomainEvents();

        user.assignRole(UserRole.USER, LATER);

        assertThat(user.domainEvents()).isEmpty();
        assertThat(user.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void recordingALoginDoesNotTouchUpdatedAt() {
        User user = activeUser();

        user.recordLogin(LATER);

        assertThat(user.lastLoginAt()).isEqualTo(LATER);
        assertThat(user.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void emailCanBeRefreshedEvenWhileDisabled() {
        User user = activeUser();
        user.disable(LATER);

        user.changeEmail(new Email("ada@newdomain.com"), LATER);

        assertThat(user.email()).isEqualTo(new Email("ada@newdomain.com"));
    }

    @Test
    void rehydrateRecordsNoEvents() {
        User user = User.rehydrate(
                UserId.generate(),
                new FirebaseUid("firebase-uid-1"),
                new Email("ada@example.com"),
                new Username("ada"),
                null,
                UserRole.ADMIN,
                UserStatus.DISABLED,
                7L,
                NOW, NOW, null);

        assertThat(user.domainEvents()).isEmpty();
        assertThat(user.role()).isEqualTo(UserRole.ADMIN);
        assertThat(user.isActive()).isFalse();
        assertThat(user.version()).isEqualTo(7L);
    }

    @Test
    void identityIsTheAggregateIdAlone() {
        UserId sharedId = UserId.generate();
        User first = User.rehydrate(sharedId, new FirebaseUid("a"), new Email("a@example.com"),
                new Username("aaa"), null, UserRole.USER, UserStatus.ACTIVE, 0L, NOW, NOW, null);
        User second = User.rehydrate(sharedId, new FirebaseUid("b"), new Email("b@example.com"),
                new Username("bbb"), null, UserRole.ADMIN, UserStatus.DISABLED, 0L, NOW, NOW, null);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    // --- optimistic locking --------------------------------------------------------

    @Test
    void aNewlyRegisteredUserStartsAtTheInitialVersion() {
        assertThat(activeUser().version()).isEqualTo(User.INITIAL_VERSION);
    }

    @Test
    void markingPersistedAdvancesTheVersion() {
        User user = activeUser();

        user.markPersisted();

        assertThat(user.version()).isEqualTo(User.INITIAL_VERSION + 1);
    }

    // --- deletion ------------------------------------------------------------------

    @Test
    void deletingAnonymisesTheProfileButKeepsTheFirebaseLink() {
        User user = activeUser();
        user.pullDomainEvents();

        user.delete(LATER);

        assertThat(user.status()).isEqualTo(UserStatus.DELETED);
        assertThat(user.isDeleted()).isTrue();
        assertThat(user.isActive()).isFalse();
        assertThat(user.displayName()).isNull();
        assertThat(user.email().value()).doesNotContain("ada@example.com").endsWith("@deleted.invalid");
        assertThat(user.username().value()).isNotEqualTo("ada");
        // Retained on purpose: it is the key that lets an already-issued token be rejected.
        assertThat(user.firebaseUid()).isEqualTo(new FirebaseUid("firebase-uid-1"));
        assertThat(user.domainEvents()).singleElement().isInstanceOf(UserAccountDeleted.class);
    }

    @Test
    void theAnonymisedValuesAreDerivedFromTheIdSoTheyStayUnique() {
        User first = activeUser();
        User second = User.register(new FirebaseUid("firebase-uid-2"), new Email("bob@example.com"),
                new Username("bob"), null, NOW);

        first.delete(LATER);
        second.delete(LATER);

        assertThat(first.email()).isNotEqualTo(second.email());
        assertThat(first.username()).isNotEqualTo(second.username());
    }

    @Test
    void deletingTwiceIsANoOp() {
        User user = activeUser();
        user.delete(LATER);
        Email tombstoneEmail = user.email();
        user.pullDomainEvents();

        user.delete(LATER.plusSeconds(60));

        assertThat(user.email()).isEqualTo(tombstoneEmail);
        assertThat(user.domainEvents()).isEmpty();
    }

    @Test
    void aDeletedUserCannotBeMutatedFurther() {
        User user = activeUser();
        user.delete(LATER);

        assertThatThrownBy(() -> user.assignRole(UserRole.ADMIN, LATER))
                .isInstanceOf(DomainRuleViolationException.class);
        assertThatThrownBy(() -> user.changeEmail(new Email("back@example.com"), LATER))
                .isInstanceOf(DomainRuleViolationException.class);
        assertThatThrownBy(() -> user.enable(LATER))
                .isInstanceOf(DomainRuleViolationException.class);
    }
}
