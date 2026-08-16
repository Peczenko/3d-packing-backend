package com.packing.backend.infra.persistence.shared;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.packing.backend.infra.persistence.jooq.tables.Users.USERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateTableTest {

    @Test
    void rejectsTheVersionColumnListedAsImmutable() {
        assertThatThrownBy(() -> new AggregateTable<>(
                                                      "User",
                                                      USERS.VERSION,
                                                      Set.of(USERS.FIREBASE_UID, USERS.VERSION)))
                                                                                                 .isInstanceOf(IllegalArgumentException.class)
                                                                                                 .hasMessageContaining("version");
    }

    @Test
    void acceptsANormalSpec() {
        AggregateTable<?> table = new AggregateTable<>(
                                                       "User",
                                                       USERS.VERSION,
                                                       Set.of(USERS.FIREBASE_UID, USERS.CREATED_AT));

        assertThat(table.immutable()).containsExactlyInAnyOrder(USERS.FIREBASE_UID, USERS.CREATED_AT);
    }
}
