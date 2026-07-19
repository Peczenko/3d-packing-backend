package com.packing.backend.infra.persistence.user;

import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;
import com.packing.backend.domain.user.Username;
import com.packing.backend.infra.persistence.jooq.tables.records.UsersRecord;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Translates between the generated jOOQ record and the {@link User} aggregate.
 *
 * <p>The column is {@code timestamp with time zone}, which jOOQ surfaces as
 * {@link OffsetDateTime}, while the domain speaks {@link Instant} — an instant has no
 * offset to get wrong. Everything is normalised to UTC on the way out.
 */
final class UserRecordMapper {

    private UserRecordMapper() {
    }

    static User toDomain(UsersRecord record) {
        return User.rehydrate(
                new UserId(record.getId()),
                new FirebaseUid(record.getFirebaseUid()),
                new Email(record.getEmail()),
                new Username(record.getUsername()),
                record.getDisplayName(),
                UserRole.valueOf(record.getRole()),
                UserStatus.valueOf(record.getStatus()),
                record.getVersion(),
                toInstant(record.getCreatedAt()),
                toInstant(record.getUpdatedAt()),
                toInstant(record.getLastLoginAt()));
    }

    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
