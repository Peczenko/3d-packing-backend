package com.packing.backend.infra.persistence.user;

import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.EmailAlreadyRegisteredException;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;
import com.packing.backend.domain.user.UsernameAlreadyTakenException;
import com.packing.backend.infra.persistence.shared.AggregateWriter;
import com.packing.backend.infra.persistence.shared.SqlConstraintViolationTranslator;
import com.packing.backend.infra.persistence.shared.Timestamps;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.packing.backend.infra.persistence.jooq.tables.Users.USERS;

@Repository
@RequiredArgsConstructor
public class JooqUserRepository implements UserRepository {

    private final DSLContext dsl;
    private final AggregateWriter writer;


    @Override
    public User save(User user) {
        constraintTranslatorFor(user).translating(() ->
                writer.upsert(UserRecordMapper.TABLE, UserRecordMapper.toRecord(user),
                        user.version()));
        user.markPersisted();
        return user;
    }

    @Override
    public void recordSignIn(UserId id, Email email, Instant updatedAt, Instant lastLoginAt) {
        constraintTranslatorFor(email).translating(() -> dsl.update(USERS)
                .set(USERS.EMAIL, email.value())
                .set(USERS.UPDATED_AT, Timestamps.toOffsetDateTime(updatedAt))
                .set(USERS.LAST_LOGIN_AT, Timestamps.toOffsetDateTime(lastLoginAt))
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
    public Optional<User> findByEmail(Email email) {
        return dsl.selectFrom(USERS)
                .where(USERS.EMAIL.eq(email.value()))
                .fetchOptional()
                .map(UserRecordMapper::toDomain);
    }

    @Override
    public Optional<User> findByUsername(Username username) {
        return dsl.selectFrom(USERS)
                .where(USERS.USERNAME.eq(username.value()))
                .fetchOptional()
                .map(UserRecordMapper::toDomain);
    }

    @Override
    public boolean existsByUsername(Username username) {
        return dsl.fetchExists(dsl.selectFrom(USERS).where(USERS.USERNAME.eq(username.value())));
    }

    private SqlConstraintViolationTranslator constraintTranslatorFor(User user) {
        return new SqlConstraintViolationTranslator(Map.of(
                "uq_users_username", () -> new UsernameAlreadyTakenException(user.username()),
                "uq_users_email", () -> new EmailAlreadyRegisteredException(user.email()),
                "uq_users_firebase_uid", () -> new ResourceConflictException(
                        "A profile already exists for Firebase uid " + user.firebaseUid())));
    }

    private SqlConstraintViolationTranslator constraintTranslatorFor(Email email) {
        Supplier<RuntimeException> conflict = () -> new EmailAlreadyRegisteredException(email);
        return new SqlConstraintViolationTranslator(Map.of("uq_users_email", conflict));
    }
}
