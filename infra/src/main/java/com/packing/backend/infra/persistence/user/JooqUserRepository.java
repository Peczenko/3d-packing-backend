package com.packing.backend.infra.persistence.user;

import com.packing.backend.core.shared.ConcurrentUpdateException;
import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.EmailAlreadyRegisteredException;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;
import com.packing.backend.domain.user.UsernameAlreadyTakenException;
import com.packing.backend.infra.persistence.shared.SqlConstraintViolationTranslator;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.packing.backend.infra.persistence.jooq.tables.Users.USERS;

/**
 * jOOQ adapter for {@link UserRepository}.
 *
 * <p>No {@code @Transactional} here: transaction boundaries belong to the application
 * services in {@code :core}, and this adapter always runs inside one of theirs.
 */
@Repository
public class JooqUserRepository implements UserRepository {

    private final DSLContext dsl;

    public JooqUserRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Upsert on the primary key, guarded by the aggregate's version.
     *
     * <p>The {@code WHERE} on the conflict branch is what makes this safe: it matches the
     * <em>stored</em> version, so an update built from a stale read affects zero rows and
     * raises instead of silently overwriting whatever changed in the meantime.
     */
    @Override
    public User save(User user) {
        long expectedVersion = user.version();
        int affected = constraintTranslatorFor(user).translating(() -> dsl.insertInto(USERS)
                .set(USERS.ID, user.id().value())
                .set(USERS.FIREBASE_UID, user.firebaseUid().value())
                .set(USERS.EMAIL, user.email().value())
                .set(USERS.USERNAME, user.username().value())
                .set(USERS.DISPLAY_NAME, user.displayName())
                .set(USERS.ROLE, user.role().name())
                .set(USERS.STATUS, user.status().name())
                // Both branches store expectedVersion + 1 so that a write always advances
                // the version by exactly one, whether it inserted or updated. Storing the
                // un-incremented value on insert would leave the aggregate one ahead of the
                // row and make the very next save fail its own optimistic check.
                .set(USERS.VERSION, expectedVersion + 1)
                .set(USERS.CREATED_AT, UserRecordMapper.toOffsetDateTime(user.createdAt()))
                .set(USERS.UPDATED_AT, UserRecordMapper.toOffsetDateTime(user.updatedAt()))
                .set(USERS.LAST_LOGIN_AT, UserRecordMapper.toOffsetDateTime(user.lastLoginAt()))
                .onConflict(USERS.ID)
                .doUpdate()
                // id, firebase_uid and created_at are immutable in the domain, so they are
                // deliberately absent from the update set.
                .set(USERS.EMAIL, user.email().value())
                .set(USERS.USERNAME, user.username().value())
                .set(USERS.DISPLAY_NAME, user.displayName())
                .set(USERS.ROLE, user.role().name())
                .set(USERS.STATUS, user.status().name())
                .set(USERS.VERSION, expectedVersion + 1)
                .set(USERS.UPDATED_AT, UserRecordMapper.toOffsetDateTime(user.updatedAt()))
                .set(USERS.LAST_LOGIN_AT, UserRecordMapper.toOffsetDateTime(user.lastLoginAt()))
                .where(USERS.VERSION.eq(expectedVersion))
                .execute());

        if (affected == 0) {
            throw new ConcurrentUpdateException(
                    "User " + user.id() + " was modified by another transaction "
                            + "(expected version " + expectedVersion + "). Re-read and retry.");
        }
        user.markPersisted();
        return user;
    }

    /**
     * Touches only the columns a sign-in owns, and does not bump the version — see
     * {@link UserRepository#recordSignIn}.
     */
    @Override
    public void recordSignIn(UserId id, Email email, Instant updatedAt, Instant lastLoginAt) {
        constraintTranslatorFor(email).translating(() -> dsl.update(USERS)
                .set(USERS.EMAIL, email.value())
                .set(USERS.UPDATED_AT, UserRecordMapper.toOffsetDateTime(updatedAt))
                .set(USERS.LAST_LOGIN_AT, UserRecordMapper.toOffsetDateTime(lastLoginAt))
                .where(USERS.ID.eq(id.value()))
                .execute());
    }

    @Override
    public Optional<User> findById(UserId id) {
        return dsl.selectFrom(USERS)
                .where(USERS.ID.eq(id.value()))
                .fetchOptional()
                .map(UserRecordMapper::toDomain);
    }

    @Override
    public Optional<User> findByFirebaseUid(FirebaseUid firebaseUid) {
        return dsl.selectFrom(USERS)
                .where(USERS.FIREBASE_UID.eq(firebaseUid.value()))
                .fetchOptional()
                .map(UserRecordMapper::toDomain);
    }

    @Override
    public boolean existsByUsername(Username username) {
        return dsl.fetchExists(dsl.selectFrom(USERS).where(USERS.USERNAME.eq(username.value())));
    }

    /**
     * Built per call so the resulting exception can name the value that actually collided.
     * Constraint names come from V1__create_users_table.sql.
     */
    private SqlConstraintViolationTranslator constraintTranslatorFor(User user) {
        return new SqlConstraintViolationTranslator(Map.of(
                "uq_users_username", () -> new UsernameAlreadyTakenException(user.username()),
                "uq_users_email", () -> new EmailAlreadyRegisteredException(user.email()),
                "uq_users_firebase_uid", () -> new ResourceConflictException(
                        "A profile already exists for Firebase uid " + user.firebaseUid())));
    }

    /** The sign-in path can only ever collide on the email it is refreshing. */
    private SqlConstraintViolationTranslator constraintTranslatorFor(Email email) {
        Supplier<RuntimeException> conflict = () -> new EmailAlreadyRegisteredException(email);
        return new SqlConstraintViolationTranslator(Map.of("uq_users_email", conflict));
    }
}
