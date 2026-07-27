package com.packing.backend.infra.persistence.user;

import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.Username;
import com.packing.backend.infra.TestcontainersConfiguration;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@JooqTest
@Import(TestcontainersConfiguration.class)
class JooqActiveUserLookupIT {

    @Autowired
    private DSLContext dsl;

    private JooqActiveUserLookup lookup() {
        return new JooqActiveUserLookup(dsl);
    }

    private User persistedUser(String uid) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        User user = User.register(new FirebaseUid(uid), new Email(uid + "@example.com"),
                new Username(uid), "Display", now);
        new JooqUserRepository(dsl).save(user);
        return user;
    }

    @Test
    void resolvesAnActiveUserToTheirId() {
        User user = persistedUser("uid-active");

        assertThat(lookup().findActiveUser(new FirebaseUid("uid-active")))
                .hasValue(user.id());
    }

    @Test
    void isEmptyForAnIdentityWithNoProfile() {
        assertThat(lookup().findActiveUser(new FirebaseUid("uid-unknown"))).isEmpty();
    }

    @Test
    void isEmptyForADisabledUser() {
        User user = persistedUser("uid-disabled");
        user.disable(Instant.now().truncatedTo(ChronoUnit.MICROS));
        new JooqUserRepository(dsl).save(user);

        assertThat(lookup().findActiveUser(new FirebaseUid("uid-disabled"))).isEmpty();
    }

    @Test
    void isEmptyForADeletedUser() {
        User user = persistedUser("uid-deleted");
        user.delete(Instant.now().truncatedTo(ChronoUnit.MICROS));
        new JooqUserRepository(dsl).save(user);

        assertThat(lookup().findActiveUser(new FirebaseUid("uid-deleted"))).isEmpty();
    }
}
