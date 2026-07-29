package com.packing.backend.infra.persistence.shared;

import org.jooq.Field;
import org.jooq.TableField;
import org.jooq.UpdatableRecord;

import java.util.Set;

/**
 * @param immutable columns the domain never changes after creation, so the conflict branch of
 *                  the upsert must leave them alone. The primary key is excluded
 *                  automatically and does not belong here.
 */
public record AggregateTable<R extends UpdatableRecord<R>>(
        String aggregateName,
        TableField<R, Long> version,
        Set<Field<?>> immutable) {

    public AggregateTable {
        immutable = Set.copyOf(immutable);
    }
}
