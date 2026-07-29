package com.packing.backend.infra.persistence.user;

import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;
import com.packing.backend.domain.user.Username;
import com.packing.backend.infra.persistence.jooq.tables.records.UsersRecord;
import com.packing.backend.infra.persistence.shared.Timestamps;

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
                Timestamps.toInstant(record.getCreatedAt()),
                Timestamps.toInstant(record.getUpdatedAt()),
                Timestamps.toInstant(record.getLastLoginAt()));
    }

    static UsersRecord toRecord(User user) {
        UsersRecord record = new UsersRecord();
        record.setId(user.id().value());
        record.setFirebaseUid(user.firebaseUid().value());
        record.setEmail(user.email().value());
        record.setUsername(user.username().value());
        record.setDisplayName(user.displayName());
        record.setRole(user.role().name());
        record.setStatus(user.status().name());
        record.setVersion(user.version());
        record.setCreatedAt(Timestamps.toOffsetDateTime(user.createdAt()));
        record.setUpdatedAt(Timestamps.toOffsetDateTime(user.updatedAt()));
        record.setLastLoginAt(Timestamps.toOffsetDateTime(user.lastLoginAt()));
        return record;
    }
}
