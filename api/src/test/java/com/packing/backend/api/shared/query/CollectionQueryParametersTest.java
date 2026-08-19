package com.packing.backend.api.shared.query;

import com.packing.backend.core.shared.InstantRange;
import com.packing.backend.core.shared.SortDirection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionQueryParametersTest {

    @Test
    void normalizesSearchAndPagingParameters() {
        assertThat(CollectionQueryParameters.search(null)).isNull();
        assertThat(CollectionQueryParameters.search("  ")).isNull();
        assertThat(CollectionQueryParameters.search("  boxes ")).isEqualTo("boxes");
        assertThat(CollectionQueryParameters.page(null)).isZero();
        assertThat(CollectionQueryParameters.page(3)).isEqualTo(3);
        assertThat(CollectionQueryParameters.size(null)).isEqualTo(20);
        assertThat(CollectionQueryParameters.size(50)).isEqualTo(50);
    }

    @Test
    void copiesSuppliedValuesDefensively() {
        Set<String> supplied = new HashSet<>(Set.of("a"));
        Set<String> copy = CollectionQueryParameters.values(supplied);

        supplied.add("b");

        assertThat(copy).containsExactly("a");
        assertThat(CollectionQueryParameters.values(null)).isEmpty();
    }

    @Test
    void resolvesDirectionFromParameterPresence() {
        assertThat(CollectionQueryParameters.direction(false, null, SortDirection.DESC))
                                                                                        .isEqualTo(SortDirection.DESC);
        assertThat(CollectionQueryParameters.direction(true, null, SortDirection.DESC))
                                                                                       .isEqualTo(SortDirection.ASC);
        assertThat(CollectionQueryParameters.direction(false, SortDirection.ASC, SortDirection.DESC))
                                                                                                     .isEqualTo(SortDirection.ASC);
    }

    @Test
    void validatesRangeBounds() {
        OffsetDateTime from = OffsetDateTime.parse("2026-08-18T00:00:00Z");
        OffsetDateTime before = OffsetDateTime.parse("2026-08-19T00:00:00Z");

        assertThat(CollectionQueryParameters.validRange(null, before)).isTrue();
        assertThat(CollectionQueryParameters.validRange(from, null)).isTrue();
        assertThat(CollectionQueryParameters.validRange(from, before)).isTrue();
        assertThat(CollectionQueryParameters.validRange(before, from)).isFalse();
        assertThat(CollectionQueryParameters.validRange(from, from)).isFalse();
    }

    @Test
    void convertsAnOffsetRangeToInstants() {
        OffsetDateTime from = OffsetDateTime.parse("2026-08-18T02:00:00+02:00");
        OffsetDateTime before = OffsetDateTime.parse("2026-08-19T02:00:00+02:00");

        assertThat(CollectionQueryParameters.range(from, before))
                                                                 .isEqualTo(new InstantRange(
                                                                                             Instant.parse("2026-08-18T00:00:00Z"),
                                                                                             Instant.parse("2026-08-19T00:00:00Z")));
    }
}
