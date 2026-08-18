package com.packing.backend.api.shared.query;

import com.packing.backend.core.shared.InstantRange;
import com.packing.backend.core.shared.SortDirection;

import java.time.OffsetDateTime;
import java.util.Set;

public final class CollectionQueryParameters {

    private CollectionQueryParameters() {
    }

    public static String search(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static int page(Integer value) {
        return value == null ? 0 : value;
    }

    public static int size(Integer value) {
        return value == null ? 20 : value;
    }

    public static SortDirection direction(boolean customSort,
                                          SortDirection supplied,
                                          SortDirection endpointDefault) {
        if (supplied != null) {
            return supplied;
        }
        return customSort ? SortDirection.ASC : endpointDefault;
    }

    public static boolean validRange(OffsetDateTime from, OffsetDateTime before) {
        return from == null || before == null || from.isBefore(before);
    }

    public static InstantRange range(OffsetDateTime from, OffsetDateTime before) {
        return new InstantRange(
                from == null ? null : from.toInstant(),
                before == null ? null : before.toInstant());
    }

    public static <T> Set<T> values(Set<T> supplied) {
        return supplied == null ? Set.of() : Set.copyOf(supplied);
    }
}
