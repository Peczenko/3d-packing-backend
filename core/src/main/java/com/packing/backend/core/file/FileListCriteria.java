package com.packing.backend.core.file;

import com.packing.backend.core.shared.InstantRange;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.file.ModelFormat;

import java.util.Objects;
import java.util.Set;

public record FileListCriteria(
        PageRequest page,
        String search,
        Set<ModelFormat> formats,
        InstantRange createdAt,
        SortField sort,
        SortDirection direction) {

    public FileListCriteria {
        page = Objects.requireNonNull(page, "page");
        formats = Set.copyOf(formats);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        sort = Objects.requireNonNull(sort, "sort");
        direction = Objects.requireNonNull(direction, "direction");
    }

    public enum SortField {
        FILENAME,
        FORMAT,
        SIZE_BYTES,
        CREATED_AT
    }
}
