package com.packing.backend.infra.persistence.shared;

import com.packing.backend.core.shared.InstantRange;
import org.jooq.Condition;
import org.jooq.Field;

import java.time.Instant;

import static org.jooq.impl.DSL.noCondition;

public final class JooqConditions {

    private JooqConditions() {
    }

    public static Condition instantRange(Field<Instant> field, InstantRange range) {
        Condition condition = noCondition();
        if (range.from() != null) {
            condition = condition.and(field.ge(range.from()));
        }
        if (range.before() != null) {
            condition = condition.and(field.lt(range.before()));
        }
        return condition;
    }
}
