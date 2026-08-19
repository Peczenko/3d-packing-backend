package com.packing.backend.api.project;

import com.packing.backend.api.shared.query.CollectionQueryParameters;
import com.packing.backend.core.project.ProjectListCriteria;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.Set;

public record ProjectListRequest(
        @Min(0) Integer page,
        @Min(1) @Max(PageRequest.MAX_SIZE) Integer size,
        @Size(min = 3, max = 100) String search,
        Set<ProjectStatus> status,
        Set<ProjectPermission> permission,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdBefore,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime updatedFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime updatedBefore,
        @Pattern(regexp = "name|status|permission|memberCount|createdAt|updatedAt") String sort,
        SortDirection direction) {

    public ProjectListRequest {
        boolean customSort = sort != null;
        page = CollectionQueryParameters.page(page);
        size = CollectionQueryParameters.size(size);
        search = CollectionQueryParameters.search(search);
        status = CollectionQueryParameters.values(status);
        permission = CollectionQueryParameters.values(permission);
        sort = sort == null ? "createdAt" : sort;
        direction = CollectionQueryParameters.direction(customSort, direction, SortDirection.DESC);
    }

    @AssertTrue(message = "status must not include DELETED")
    public boolean hasVisibleStatuses() {
        return !status.contains(ProjectStatus.DELETED);
    }

    @AssertTrue(message = "createdFrom must be before createdBefore")
    public boolean hasValidCreatedAtRange() {
        return CollectionQueryParameters.validRange(createdFrom, createdBefore);
    }

    @AssertTrue(message = "updatedFrom must be before updatedBefore")
    public boolean hasValidUpdatedAtRange() {
        return CollectionQueryParameters.validRange(updatedFrom, updatedBefore);
    }

    public ProjectListCriteria toCriteria() {
        return new ProjectListCriteria(
                                       new PageRequest(page, size),
                                       search,
                                       status,
                                       permission,
                                       CollectionQueryParameters.range(createdFrom, createdBefore),
                                       CollectionQueryParameters.range(updatedFrom, updatedBefore),
                                       switch (sort) {
                                           case "name" -> ProjectListCriteria.SortField.NAME;
                                           case "status" -> ProjectListCriteria.SortField.STATUS;
                                           case "permission" -> ProjectListCriteria.SortField.PERMISSION;
                                           case "memberCount" -> ProjectListCriteria.SortField.MEMBER_COUNT;
                                           case "createdAt" -> ProjectListCriteria.SortField.CREATED_AT;
                                           case "updatedAt" -> ProjectListCriteria.SortField.UPDATED_AT;
                                           default -> throw new IllegalStateException("Unexpected sort: " + sort);
                                       },
                                       direction);
    }
}
