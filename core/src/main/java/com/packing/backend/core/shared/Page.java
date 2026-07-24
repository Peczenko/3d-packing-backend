package com.packing.backend.core.shared;

import java.util.List;
import java.util.Objects;

/**
 * @param size the requested page size, not the number of elements returned
 */
public record Page<T>(List<T> content, int page, int size, long totalElements) {

    public Page {
        content = List.copyOf(Objects.requireNonNull(content, "content"));
    }

    public int totalPages() {
        return size <= 0 ? 0 : (int) Math.ceilDiv(totalElements, size);
    }
}
