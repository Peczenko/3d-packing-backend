package com.packing.backend.infra.persistence.shared;

import org.jooq.Field;
import org.jooq.TableField;
import org.jooq.UpdatableRecord;

import java.util.Set;

public record AggregateTable<R extends UpdatableRecord<R>>(
        String aggregateName,
        TableField<R, Long> version,
        Set<Field<?>> immutable) {

    public AggregateTable {
        immutable = Set.copyOf(immutable);
        if (immutable.contains(version)) {
            throw new IllegalArgumentException("The version column must stay mutable");
        }
    }
}
