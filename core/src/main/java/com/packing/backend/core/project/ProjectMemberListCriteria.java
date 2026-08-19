package com.packing.backend.core.project;

import com.packing.backend.core.shared.InstantRange;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.project.ProjectPermission;

import java.util.Objects;
import java.util.Set;

public record ProjectMemberListCriteria(
        PageRequest page,
        String search,
        Set<ProjectPermission> permissions,
        InstantRange addedAt,
        SortField sort,
        SortDirection direction) {

    public ProjectMemberListCriteria {
        page = Objects.requireNonNull(page, "page");
        permissions = Set.copyOf(permissions);
        addedAt = Objects.requireNonNull(addedAt, "addedAt");
        sort = Objects.requireNonNull(sort, "sort");
        direction = Objects.requireNonNull(direction, "direction");
    }

    public enum SortField {
        USERNAME,
        DISPLAY_NAME,
        PERMISSION,
        ADDED_AT
    }
}
