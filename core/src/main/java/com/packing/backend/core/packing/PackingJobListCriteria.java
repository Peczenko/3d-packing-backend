package com.packing.backend.core.packing;

import com.packing.backend.core.shared.InstantRange;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.packing.PackingJobStatus;

import java.util.Objects;
import java.util.Set;

public record PackingJobListCriteria(
        PageRequest page,
        String search,
        Set<PackingJobStatus> statuses,
        InstantRange createdAt,
        InstantRange startedAt,
        InstantRange finishedAt,
        SortField sort,
        SortDirection direction) {

    public PackingJobListCriteria {
        page = Objects.requireNonNull(page, "page");
        statuses = Set.copyOf(statuses);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
        sort = Objects.requireNonNull(sort, "sort");
        direction = Objects.requireNonNull(direction, "direction");
    }

    public enum SortField {
        STATUS,
        MAX_RUNTIME_SECONDS,
        ENGINE_VERSION,
        CREATED_AT,
        STARTED_AT,
        FINISHED_AT,
        RESULT_FILE_NAME,
        RESULT_SIZE_BYTES
    }
}
