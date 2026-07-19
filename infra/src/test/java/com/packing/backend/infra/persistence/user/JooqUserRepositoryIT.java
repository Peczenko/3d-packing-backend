package com.packing.backend.infra.persistence.user;

import com.packing.backend.core.shared.ConcurrentUpdateException;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.EmailAlreadyRegisteredException;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;
import com.packing.backend.domain.user.Username;
import com.packing.backend.domain.user.UsernameAlreadyTakenException;
import com.packing.backend.infra.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;
import org.jooq.DSLContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the generated jOOQ code against a real PostgreSQL container.
 *
 * <p>This is the compensating control for generating offline: {@code DDLDatabase} reverse
 * engineers the migrations through an in-memory <em>H2</em> database, so the generated
 * column types are H2's interpretation of the DDL, not PostgreSQL's. Only executing the
 * queries against real PostgreSQL proves the two agree.
 *
 * <p>{@code @JooqTest} imports {@code FlywayAutoConfiguration} and
 * {@code ServiceConnectionAutoConfiguration} and does not import
 * {@code TestDatabaseAutoConfiguration}, so the migrations are applied to the container
 * rather than to an embedded database. Each test runs in a transaction that is rolled
 * back afterwards.
 */
@JooqTest
@Import(TestcontainersConfiguration.class)
class JooqUserRepositoryIT {

    @Autowired
    private DSLContext dsl;

    private JooqUserRepository repository() {
        return new JooqUserRepository(dsl);
    }

    private User newUser(String uid, String email, String username) {
        // Truncated to microseconds: PostgreSQL timestamps have microsecond resolution,
        // so nanosecond input would not survive the round trip.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return User.register(new FirebaseUid(uid), new Email(email), new Username(username),
                "Display Name", now);
    }

    @Test
    void savesAndReadsBackEveryField() {
        User saved = newUser("uid-1", "ada@example.com", "ada");
        saved.recordLogin(saved.createdAt());
        repository().save(saved);

        Optional<User> found = repository().findByFirebaseUid(new FirebaseUid("uid-1"));

        assertThat(found).hasValueSatisfying(user -> {
            assertThat(user.id()).isEqualTo(saved.id());
            assertThat(user.email()).isEqualTo(new Email("ada@example.com"));
            assertThat(user.username()).isEqualTo(new Username("ada"));
            assertThat(user.displayName()).isEqualTo("Display Name");
            assertThat(user.role()).isEqualTo(UserRole.USER);
            assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
            // The real assertion of this suite: timestamptz survives the
            // Instant -> OffsetDateTime -> PostgreSQL -> OffsetDateTime -> Instant trip.
            assertThat(user.createdAt()).isEqualTo(saved.createdAt());
            assertThat(user.lastLoginAt()).isEqualTo(saved.createdAt());
        });
    }

    @Test
    void aNullDisplayNameAndLoginTimeRoundTrip() {
        User saved = User.register(new FirebaseUid("uid-2"), new Email("nulls@example.com"),
                new Username("nulls"), null, Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository().save(saved);

        assertThat(repository().findById(saved.id())).hasValueSatisfying(user -> {
            assertThat(user.displayName()).isNull();
            assertThat(user.lastLoginAt()).isNull();
        });
    }

    @Test
    void savingTwiceUpdatesInPlaceRatherThanInserting() {
        User user = newUser("uid-3", "update@example.com", "before");
        repository().save(user);

        Instant later = user.createdAt().plusSeconds(60);
        user.changeProfile(new Username("after"), "Changed", later);
        user.assignRole(UserRole.ADMIN, later);
        repository().save(user);

        assertThat(repository().findById(user.id())).hasValueSatisfying(reloaded -> {
            assertThat(reloaded.username()).isEqualTo(new Username("after"));
            assertThat(reloaded.displayName()).isEqualTo("Changed");
            assertThat(reloaded.role()).isEqualTo(UserRole.ADMIN);
            assertThat(reloaded.createdAt()).isEqualTo(user.createdAt());
        });
        assertThat(dsl.fetchCount(
                com.packing.backend.infra.persistence.jooq.tables.Users.USERS)).isEqualTo(1);
    }

    @Test
    void existsByUsernameReflectsWhatIsStored() {
        repository().save(newUser("uid-4", "exists@example.com", "taken"));

        assertThat(repository().existsByUsername(new Username("taken"))).isTrue();
        assertThat(repository().existsByUsername(new Username("free"))).isFalse();
    }

    @Test
    void aDuplicateUsernameIsTranslatedIntoTheDomainConflict() {
        repository().save(newUser("uid-5", "first@example.com", "duplicate"));
        User clashing = newUser("uid-6", "second@example.com", "duplicate");

        assertThatThrownBy(() -> repository().save(clashing))
                .isInstanceOf(UsernameAlreadyTakenException.class);
    }

    @Test
    void aDuplicateEmailIsTranslatedIntoTheDomainConflict() {
        repository().save(newUser("uid-7", "same@example.com", "userone"));
        User clashing = newUser("uid-8", "same@example.com", "usertwo");

        assertThatThrownBy(() -> repository().save(clashing))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void lookingUpAnAbsentUserReturnsEmpty() {
        assertThat(repository().findById(UserId.generate())).isEmpty();
        assertThat(repository().findByFirebaseUid(new FirebaseUid("nobody"))).isEmpty();
    }

    // --- optimistic locking --------------------------------------------------------

    @Test
    void savingAdvancesTheStoredVersionAndKeepsTheAggregateInStep() {
        User user = newUser("uid-10", "version@example.com", "versioned");
        repository().save(user);
        long afterInsert = repository().findById(user.id()).orElseThrow().version();
        // If these drift apart, the next save fails its own optimistic check.
        assertThat(user.version()).isEqualTo(afterInsert);

        user.changeProfile(new Username("versioned2"), null, user.createdAt().plusSeconds(1));
        repository().save(user);

        long afterUpdate = repository().findById(user.id()).orElseThrow().version();
        assertThat(afterUpdate).isEqualTo(afterInsert + 1);
        assertThat(user.version()).isEqualTo(afterUpdate);
    }

    @Test
    void repeatedSavesOfTheSameAggregateKeepSucceeding() {
        User user = newUser("uid-17", "repeatsave@example.com", "repeatsave");

        for (int i = 0; i < 3; i++) {
            user.changeProfile(new Username("repeatsave" + i), null,
                    user.createdAt().plusSeconds(i + 1L));
            assertThatCode(() -> repository().save(user)).doesNotThrowAnyException();
        }

        assertThat(repository().findById(user.id()).orElseThrow().username())
                .isEqualTo(new Username("repeatsave2"));
    }

    /**
     * The scenario the lock exists for: two readers, one writes, the other's full-aggregate
     * write must fail rather than silently reverting the first change.
     */
    @Test
    void aWriteBuiltOnAStaleReadIsRejected() {
        User user = newUser("uid-11", "stale@example.com", "stale");
        repository().save(user);

        User readerOne = repository().findById(user.id()).orElseThrow();
        User readerTwo = repository().findById(user.id()).orElseThrow();
        Instant later = user.createdAt().plusSeconds(1);

        readerOne.assignRole(UserRole.ADMIN, later);
        repository().save(readerOne);

        readerTwo.changeProfile(new Username("stalewrite"), null, later);
        assertThatThrownBy(() -> repository().save(readerTwo))
                .isInstanceOf(ConcurrentUpdateException.class);

        // The promotion survived.
        assertThat(repository().findById(user.id()).orElseThrow().role()).isEqualTo(UserRole.ADMIN);
    }

    // --- sign-in path --------------------------------------------------------------

    /**
     * The narrow sign-in write must not touch role, status, username or version — that is
     * what stops a concurrent promotion being reverted by a routine /me call.
     */
    @Test
    void recordingASignInTouchesOnlyEmailAndTimestamps() {
        User user = newUser("uid-12", "signin@example.com", "signin");
        user.assignRole(UserRole.ADMIN, user.createdAt());
        repository().save(user);
        long versionAfterSave = repository().findById(user.id()).orElseThrow().version();

        Instant signInAt = user.createdAt().plusSeconds(600);
        repository().recordSignIn(user.id(), new Email("moved@example.com"), signInAt, signInAt);

        assertThat(repository().findById(user.id())).hasValueSatisfying(reloaded -> {
            assertThat(reloaded.email()).isEqualTo(new Email("moved@example.com"));
            assertThat(reloaded.lastLoginAt()).isEqualTo(signInAt);
            assertThat(reloaded.role()).isEqualTo(UserRole.ADMIN);
            assertThat(reloaded.username()).isEqualTo(new Username("signin"));
            assertThat(reloaded.version()).isEqualTo(versionAfterSave);
        });
    }

    @Test
    void repeatedSignInsNeverConflict() {
        User user = newUser("uid-13", "repeat@example.com", "repeat");
        repository().save(user);
        long versionAfterSave = repository().findById(user.id()).orElseThrow().version();

        for (int i = 1; i <= 3; i++) {
            Instant at = user.createdAt().plusSeconds(i);
            repository().recordSignIn(user.id(), user.email(), at, at);
        }

        // Unchanged: concurrent /me calls must never contend on the optimistic lock.
        assertThat(repository().findById(user.id()).orElseThrow().version())
                .isEqualTo(versionAfterSave);
    }

    // --- soft deletion -------------------------------------------------------------

    @Test
    void deletionLeavesAnAnonymisedTombstoneThatStillResolvesByFirebaseUid() {
        User user = newUser("uid-14", "gone@example.com", "gone");
        repository().save(user);

        user.delete(user.createdAt().plusSeconds(1));
        repository().save(user);

        assertThat(repository().findByFirebaseUid(new FirebaseUid("uid-14")))
                .hasValueSatisfying(tombstone -> {
                    assertThat(tombstone.status()).isEqualTo(UserStatus.DELETED);
                    assertThat(tombstone.email().value()).doesNotContain("gone@example.com");
                    assertThat(tombstone.displayName()).isNull();
                });
    }

    /** Anonymisation releases the real email and username for someone else to use. */
    @Test
    void theOriginalEmailAndUsernameAreFreedAfterDeletion() {
        User user = newUser("uid-15", "reusable@example.com", "reusable");
        repository().save(user);
        user.delete(user.createdAt().plusSeconds(1));
        repository().save(user);

        User newcomer = newUser("uid-16", "reusable@example.com", "reusable");

        assertThatCode(() -> repository().save(newcomer)).doesNotThrowAnyException();
    }
}
