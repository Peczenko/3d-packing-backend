package com.packing.backend.infra.persistence.shared;

import com.packing.backend.core.shared.ConcurrentUpdateException;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.UpdatableRecord;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place that knows how an optimistically locked aggregate is written.
 *
 * <p>No {@code @Transactional}: transaction boundaries belong to the application services in
 * {@code :core}, and this always runs inside one of theirs.
 */
@Component
@RequiredArgsConstructor
public class AggregateWriter {

    private final DSLContext dsl;

    /**
     * Upsert on the primary key, guarded by the version the aggregate was read at.
     *
     * <p>The {@code WHERE} on the conflict branch matches the <em>stored</em> version, so an
     * update built from a stale read affects zero rows and raises instead of silently
     * overwriting a concurrent change.
     *
     * @throws ConcurrentUpdateException if the stored version has moved on
     */
    public <R extends UpdatableRecord<R>> void upsert(AggregateTable<R> table,
                                                      R record,
                                                      long expectedVersion) {
        // Both branches store expectedVersion + 1, so a write always advances the version by
        // exactly one whether it inserted or updated. Storing the un-incremented value on
        // insert would leave the aggregate one ahead of the row and make the very next save
        // fail its own optimistic check.
        record.set(table.version(), expectedVersion + 1);

        Table<R> target = record.getTable();
        List<TableField<R, ?>> primaryKey = target.getPrimaryKey().getFields();

        // Built from the record's fields rather than from jOOQ's `changed` flags: a mapper
        // that leaves a nullable column unset must still produce a complete row. A stream
        // collector cannot be used here — Collectors.toMap throws on null values, and
        // deleted_at and last_login_at are routinely null.
        Map<Field<?>, Object> insert = new LinkedHashMap<>();
        Map<Field<?>, Object> update = new LinkedHashMap<>();
        for (Field<?> field : record.fields()) {
            Object value = record.get(field);
            insert.put(field, value);
            if (!primaryKey.contains(field) && !table.immutable().contains(field)) {
                update.put(field, value);
            }
        }

        int affected = dsl.insertInto(target)
                .set(insert)
                .onConflict(primaryKey)
                .doUpdate()
                .set(update)
                .where(table.version().eq(expectedVersion))
                .execute();

        if (affected == 0) {
            throw new ConcurrentUpdateException(
                    table.aggregateName() + " " + record.get(primaryKey.get(0))
                            + " was modified by another transaction (expected version "
                            + expectedVersion + "). Re-read and retry.");
        }
    }
}
