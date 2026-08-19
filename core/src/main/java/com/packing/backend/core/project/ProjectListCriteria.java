package com.packing.backend.core.project;

import com.packing.backend.core.shared.InstantRange;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;

import java.util.Objects;
import java.util.Set;

public record ProjectListCriteria(
        PageRequest page,
        String search,
        Set<ProjectStatus> statuses,
        Set<ProjectPermission> permissions,
        InstantRange createdAt,
        InstantRange updatedAt,
        SortField sort,
        SortDirection direction) {

    public ProjectListCriteria {
        page = Objects.requireNonNull(page, "page");
        statuses = Set.copyOf(statuses);
        permissions = Set.copyOf(permissions);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        sort = Objects.requireNonNull(sort, "sort");
        direction = Objects.requireNonNull(direction, "direction");
    }

    public enum SortField {
        NAME,
        STATUS,
        PERMISSION,
        MEMBER_COUNT,
        CREATED_AT,
        UPDATED_AT
    }
}
