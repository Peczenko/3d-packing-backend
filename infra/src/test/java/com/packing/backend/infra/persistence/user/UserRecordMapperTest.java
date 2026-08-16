package com.packing.backend.infra.persistence.user;

import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;
import com.packing.backend.domain.user.Username;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserRecordMapperTest {

    private static final Instant NOW = Instant.parse("2026-07-28T10:15:30Z");

    @Test
    void roundTripsEveryColumn() {
        User user = User.rehydrate(UserId.generate(),
                                   new FirebaseUid("uid-1"),
                                   new Email("a@example.com"),
                                   new Username("alice"),
                                   "Alice",
                                   UserRole.ADMIN,
                                   UserStatus.ACTIVE,
                                   7L,
                                   NOW,
                                   NOW.plusSeconds(1),
                                   NOW.plusSeconds(2));

        User result = UserRecordMapper.toDomain(UserRecordMapper.toRecord(user));

        assertThat(result.id()).isEqualTo(user.id());
        assertThat(result.firebaseUid()).isEqualTo(user.firebaseUid());
        assertThat(result.email()).isEqualTo(user.email());
        assertThat(result.username()).isEqualTo(user.username());
        assertThat(result.displayName()).isEqualTo(user.displayName());
        assertThat(result.role()).isEqualTo(UserRole.ADMIN);
        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.version()).isEqualTo(7L);
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(result.updatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(result.lastLoginAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void roundTripsANullLastLoginAt() {
        User user = User.rehydrate(UserId.generate(),
                                   new FirebaseUid("uid-2"),
                                   new Email("b@example.com"),
                                   new Username("bob"),
                                   null,
                                   UserRole.USER,
                                   UserStatus.DELETED,
                                   0L,
                                   NOW,
                                   NOW,
                                   null);

        User result = UserRecordMapper.toDomain(UserRecordMapper.toRecord(user));

        assertThat(result.lastLoginAt()).isNull();
        assertThat(result.displayName()).isNull();
        assertThat(result.status()).isEqualTo(UserStatus.DELETED);
    }
}
