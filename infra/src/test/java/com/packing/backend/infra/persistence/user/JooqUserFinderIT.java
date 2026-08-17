package com.packing.backend.infra.persistence.user;

import com.packing.backend.core.user.UserSearchResult;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;
import com.packing.backend.domain.user.Username;
import com.packing.backend.infra.TestcontainersConfiguration;
import com.packing.backend.infra.persistence.shared.AggregateWriter;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

@JooqTest
@Import(TestcontainersConfiguration.class)
class JooqUserFinderIT {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

    @Autowired
    private DSLContext dsl;

    private JooqUserFinder finder() {
        return new JooqUserFinder(dsl);
    }

    private User persist(String uid, String email, String username,
                         String displayName, UserStatus status) {
        User user = User.rehydrate(UserId.generate(),
                                   new FirebaseUid(uid),
                                   new Email(email),
                                   new Username(username),
                                   displayName,
                                   UserRole.USER,
                                   status,
                                   User.INITIAL_VERSION,
                                   NOW,
                                   NOW,
                                   null);
        new JooqUserRepository(dsl, new AggregateWriter(dsl)).save(user);
        return user;
    }

    @Test
    void searchesAllSupportedFieldsInPriorityOrderAndExcludesDeletedUsers() {
        User username = persist("uid-username", "one@example.com", "john_builder", "One", UserStatus.ACTIVE);
        User display = persist("uid-display", "two@example.com", "builder_two", "John Smith", UserStatus.DISABLED);
        User email = persist("uid-email", "john@example.com", "email_match", "Three", UserStatus.ACTIVE);
        persist("uid-deleted", "deleted@example.com", "john_deleted", "John Deleted", UserStatus.DELETED);

        assertThat(finder().search("JOHN", 10))
                                               .extracting(UserSearchResult::id, UserSearchResult::status)
                                               .containsExactly(
                                                                tuple(username.id(), UserStatus.ACTIVE),
                                                                tuple(display.id(), UserStatus.DISABLED),
                                                                tuple(email.id(), UserStatus.ACTIVE));
    }

    @Test
    void treatsSqlWildcardCharactersAsLiteralText() {
        User literal = persist("uid-literal", "literal@example.com", "literal_user", "Code A_B 100%", UserStatus.ACTIVE);
        persist("uid-wildcard", "wildcard@example.com", "wildcard_user", "Code AXB 1000", UserStatus.ACTIVE);

        assertThat(finder().search("A_B", 10)).extracting(UserSearchResult::id)
                                              .containsExactly(literal.id());
        assertThat(finder().search("100%", 10)).extracting(UserSearchResult::id)
                                               .containsExactly(literal.id());
    }

    @Test
    void appliesTheLimitAfterDeterministicOrdering() {
        User first = persist("uid-first", "first@example.com", "a_alpha", null, UserStatus.ACTIVE);
        persist("uid-second", "second@example.com", "b_alpha", null, UserStatus.ACTIVE);

        assertThat(finder().search("alpha", 1)).extracting(UserSearchResult::id)
                                               .containsExactly(first.id());
    }
}
