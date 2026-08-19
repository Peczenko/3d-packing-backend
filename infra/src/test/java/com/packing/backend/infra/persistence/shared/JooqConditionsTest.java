package com.packing.backend.infra.persistence.shared;

import com.packing.backend.core.shared.InstantRange;
import org.jooq.Field;
import org.jooq.Query;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.packing.backend.infra.persistence.shared.JooqConditions.instantRange;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.selectOne;

class JooqConditionsTest {

    private static final Field<Instant> CREATED_AT = field(name("created_at"), Instant.class);

    @Test
    void createsAnInclusiveFromAndExclusiveBeforeCondition() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant before = Instant.parse("2026-09-01T00:00:00Z");

        Query query = selectOne().where(instantRange(CREATED_AT, new InstantRange(from, before)));

        assertThat(query.getSQL()).contains("\"created_at\" >=")
                                  .contains("\"created_at\" <");
        assertThat(query.getBindValues()).containsExactly(from, before);
    }

    @Test
    void omitsMissingRangeBounds() {
        Instant before = Instant.parse("2026-09-01T00:00:00Z");

        Query query = selectOne().where(instantRange(CREATED_AT, new InstantRange(null, before)));

        assertThat(query.getSQL()).doesNotContain("\"created_at\" >=")
                                  .contains("\"created_at\" <");
        assertThat(query.getBindValues()).containsExactly(before);
    }
}
