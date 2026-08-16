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

@Component
@RequiredArgsConstructor
public class AggregateWriter {

    private final DSLContext dsl;

    public <R extends UpdatableRecord<R>> void upsert(AggregateTable<R> table,
                                                      R record,
                                                      long expectedVersion) {
        record.set(table.version(), expectedVersion + 1);

        Table<R> target = record.getTable();
        List<TableField<R, ?>> primaryKey = target.getPrimaryKey()
                                                  .getFields();

        Map<Field<?>, Object> insert = new LinkedHashMap<>();
        Map<Field<?>, Object> update = new LinkedHashMap<>();
        for (Field<?> field : record.fields()) {
            Object value = record.get(field);
            insert.put(field, value);
            if (!primaryKey.contains(field) && !table.immutable()
                                                     .contains(field)) {
                update.put(field, value);
            }
        }

        int affected = dsl.insertInto(target)
                          .set(insert)
                          .onConflict(primaryKey)
                          .doUpdate()
                          .set(update)
                          .where(table.version()
                                      .eq(expectedVersion))
                          .execute();

        if (affected == 0) {
            throw new ConcurrentUpdateException(
                                                table.aggregateName() + " " + record.get(primaryKey.get(0))
                                                        + " was modified by another transaction (expected version "
                                                        + expectedVersion + "). Re-read and retry.");
        }
    }
}
