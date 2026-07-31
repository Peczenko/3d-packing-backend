package com.packing.backend.infra.persistence.shared;

import org.jooq.Field;
import org.jooq.TableField;
import org.jooq.impl.DSL;

public final class RawColumns {

    private RawColumns() {
    }

    public static Field<String> untyped(TableField<?, ?> column) {
        return DSL.field(column.getUnqualifiedName(), String.class);
    }
}
