package com.packing.backend.infra.persistence.shared;

import com.packing.backend.core.shared.ConcurrentUpdateException;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.Username;
import com.packing.backend.infra.TestcontainersConfiguration;
import com.packing.backend.infra.persistence.user.JooqUserRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.packing.backend.infra.persistence.jooq.tables.Users.USERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JooqTest
@Import(TestcontainersConfiguration.class)
class AggregateWriterIT {

    @Autowired
    private DSLContext dsl;

    private JooqUserRepository repository() {
        return new JooqUserRepository(dsl, new AggregateWriter(dsl));
    }

    private static Instant now() {
        return Instant.now()
                      .truncatedTo(ChronoUnit.MICROS);
    }

    private User persistedUser() {
        User user = User.register(new FirebaseUid("uid-writer"),
                                  new Email("writer@example.com"),
                                  new Username("writer"),
                                  "Writer",
                                  now());
        repository().save(user);
        return user;
    }

    @Test
    void insertAndUpdateBothAdvanceTheVersionByExactlyOne() {
        User user = User.register(new FirebaseUid("uid-v"),
                                  new Email("v@example.com"),
                                  new Username("vuser"),
                                  "V",
                                  now());

        repository().save(user);
        assertThat(user.version()).isEqualTo(1L);

        user.changeProfile(new Username("vuser"), "V2", now());
        repository().save(user);

        assertThat(repository().findById(user.id())
                               .orElseThrow()
                               .version()).isEqualTo(2L);
    }

    @Test
    void aStaleWriteIsRejectedRatherThanClobberingAConcurrentChange() {
        User user = persistedUser();
        User concurrent = repository().findById(user.id())
                                      .orElseThrow();
        concurrent.changeProfile(new Username("writer"), "Winner", now());
        repository().save(concurrent);

        user.changeProfile(new Username("writer"), "Loser", now());

        assertThatThrownBy(() -> repository().save(user))
                                                         .isInstanceOf(ConcurrentUpdateException.class)
                                                         .hasMessageContaining(user.id()
                                                                                   .toString())
                                                         .hasMessageContaining("User");
    }

    @Test
    void immutableColumnsAreNotOverwrittenOnTheConflictBranch() {
        User user = persistedUser();
        Instant originalCreatedAt = repository().findById(user.id())
                                                .orElseThrow()
                                                .createdAt();

        dsl.update(USERS)
           .set(USERS.CREATED_AT, now().plusSeconds(3_600))
           .where(USERS.ID.eq(user.id()))
           .execute();
        Instant tampered = repository().findById(user.id())
                                       .orElseThrow()
                                       .createdAt();

        user.changeProfile(new Username("writer"), "Renamed", now());
        repository().save(user);

        assertThat(repository().findById(user.id())
                               .orElseThrow()
                               .createdAt())
                                            .isEqualTo(tampered)
                                            .isNotEqualTo(originalCreatedAt);
    }
}
