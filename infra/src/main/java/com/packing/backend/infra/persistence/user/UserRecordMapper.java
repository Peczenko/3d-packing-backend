package com.packing.backend.infra.persistence.user;

import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.Username;
import com.packing.backend.infra.persistence.jooq.tables.records.UsersRecord;
import com.packing.backend.infra.persistence.shared.AggregateTable;
import org.jooq.Field;

import java.util.Set;

import static com.packing.backend.infra.persistence.jooq.tables.Users.USERS;

final class UserRecordMapper {

    static final AggregateTable<UsersRecord> TABLE = new AggregateTable<>(
                                                                          "User",
                                                                          USERS.VERSION,
                                                                          Set.<Field<?>>of(USERS.FIREBASE_UID, USERS.CREATED_AT));

    private UserRecordMapper() {
    }

    static User toDomain(UsersRecord record) {
        return User.rehydrate(
                              record.getId(),
                              new FirebaseUid(record.getFirebaseUid()),
                              new Email(record.getEmail()),
                              new Username(record.getUsername()),
                              record.getDisplayName(),
                              record.getRole(),
                              record.getStatus(),
                              record.getVersion(),
                              record.getCreatedAt(),
                              record.getUpdatedAt(),
                              record.getLastLoginAt());
    }

    static UsersRecord toRecord(User user) {
        UsersRecord record = new UsersRecord();
        record.setId(user.id());
        record.setFirebaseUid(user.firebaseUid()
                                  .value());
        record.setEmail(user.email()
                            .value());
        record.setUsername(user.username()
                               .value());
        record.setDisplayName(user.displayName());
        record.setRole(user.role());
        record.setStatus(user.status());
        record.setVersion(user.version());
        record.setCreatedAt(user.createdAt());
        record.setUpdatedAt(user.updatedAt());
        record.setLastLoginAt(user.lastLoginAt());
        return record;
    }
}
