package com.packing.backend.infra.persistence.shared;

import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import org.jooq.DSLContext;
import org.jooq.OrderField;
import org.jooq.Record;
import org.jooq.SelectOrderByStep;

import java.util.List;
import java.util.function.Function;

public final class Paging {

    private Paging() {
    }

    // Takes the query BEFORE it is ordered, and the sort separately. Two reasons, both
    // measured: jOOQ's builder mutates in place, so an already-paged query handed here would
    // be counted through its own LIMIT; and fetchCount re-emits the ORDER BY inside its derived
    // table, which costs a backward index scan and the parallel plan. orderBy() returns a type
    // that is not a SelectOrderByStep, so neither mistake compiles.
    public static <R extends Record, T> Page<T> fetch(DSLContext dsl,
                                                      SelectOrderByStep<R> query,
                                                      List<? extends OrderField<?>> orderBy,
                                                      PageRequest request,
                                                      Function<? super R, T> mapper) {
        long total = dsl.fetchCount(query);
        List<T> content = query.orderBy(orderBy)
                .offset(request.offset())
                .limit(request.size())
                .fetch()
                .map(mapper::apply);
        return new Page<>(content, request.page(), request.size(), total);
    }
}
