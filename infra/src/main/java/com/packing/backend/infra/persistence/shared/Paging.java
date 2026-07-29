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
     * <p>{@code fetchCount} wraps {@code query} verbatim as {@code select count(*) from (...) x}.
     * PostgreSQL cannot flatten that derived table away once it carries its own {@code ORDER BY}
     * or a correlated subquery in the select list — {@code JooqProjectFinder.listForMember}
     * carries both, an {@code ORDER BY} and the {@code MEMBER_COUNT} scalar subquery — so the
     * count still sorts every matching row and still evaluates the per-row subplan. Separately,
     * {@code query} is mutated in place: {@code .offset()}/{@code .limit()} return {@code this}.
     * Counting first, as done here, is correct, but the same {@code SelectLimitStep} must never
     * be passed to this method twice or reused afterward. Keep a paged query's projection
     * minimal, since the count re-executes the whole select list and ordering, and build the
     * query inline in the argument position, as all three current call sites do.
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
