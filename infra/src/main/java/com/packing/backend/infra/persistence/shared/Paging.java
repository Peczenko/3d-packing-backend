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
