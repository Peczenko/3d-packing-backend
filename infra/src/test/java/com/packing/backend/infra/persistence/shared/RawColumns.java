package com.packing.backend.infra.persistence.shared;

import org.jooq.Field;
import org.jooq.TableField;
import org.jooq.impl.DSL;

// Forced types give every enum and id column its domain type, so a test that deliberately stores
// a value the domain cannot represent has to address the column untyped.
public final class RawColumns {

    private RawColumns() {
    }

    public static Field<String> untyped(TableField<?, ?> column) {
        return DSL.field(column.getUnqualifiedName(), String.class);
    }
}
