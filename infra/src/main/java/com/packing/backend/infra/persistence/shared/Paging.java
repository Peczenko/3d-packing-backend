package com.packing.backend.infra.persistence.shared;

import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectLimitStep;

import java.util.List;
import java.util.function.Function;

public final class Paging {

    private Paging() {
    }

    /**
     * Counts, then fetches the page. Two statements rather than a {@code count(*) over()}
     * window: an out-of-range page returns no rows and therefore no total, which would need a
     * fallback count anyway.
     *
     * <p>{@code fetchCount} wraps the query as a derived table, which is only cheap because
     * listing queries carry no MULTISET projection. Do not add one to a query that comes
     * through here.
     */
    public static <R extends Record, T> Page<T> fetch(DSLContext dsl,
                                                      SelectLimitStep<R> query,
                                                      PageRequest request,
                                                      Function<? super R, T> mapper) {
        long total = dsl.fetchCount(query);
        List<T> content = query.offset(request.offset())
                .limit(request.size())
                .fetch()
                .map(mapper::apply);
        return new Page<>(content, request.page(), request.size(), total);
    }
}
