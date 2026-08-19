package com.packing.backend.api.project;

import com.packing.backend.api.shared.query.CollectionQueryParameters;
import com.packing.backend.core.project.ProjectMemberListCriteria;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.project.ProjectPermission;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.Set;

public record ProjectMemberListRequest(
        @Min(0) Integer page,
        @Min(1) @Max(PageRequest.MAX_SIZE) Integer size,
        @Size(min = 3, max = 100) String search,
        Set<ProjectPermission> permission,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime addedFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime addedBefore,
        @Pattern(regexp = "username|displayName|permission|addedAt") String sort,
        SortDirection direction) {

    public ProjectMemberListRequest {
        boolean customSort = sort != null;
        page = CollectionQueryParameters.page(page);
        size = CollectionQueryParameters.size(size);
        search = CollectionQueryParameters.search(search);
        permission = CollectionQueryParameters.values(permission);
        sort = sort == null ? "addedAt" : sort;
        direction = CollectionQueryParameters.direction(customSort, direction, SortDirection.ASC);
    }

    @AssertTrue(message = "addedFrom must be before addedBefore")
    public boolean hasValidAddedAtRange() {
        return CollectionQueryParameters.validRange(addedFrom, addedBefore);
    }

    public ProjectMemberListCriteria toCriteria() {
        return new ProjectMemberListCriteria(
                                             new PageRequest(page, size),
                                             search,
                                             permission,
                                             CollectionQueryParameters.range(addedFrom, addedBefore),
                                             switch (sort) {
                                                 case "username" -> ProjectMemberListCriteria.SortField.USERNAME;
                                                 case "displayName" -> ProjectMemberListCriteria.SortField.DISPLAY_NAME;
                                                 case "permission" -> ProjectMemberListCriteria.SortField.PERMISSION;
                                                 case "addedAt" -> ProjectMemberListCriteria.SortField.ADDED_AT;
                                                 default -> throw new IllegalStateException("Unexpected sort: " + sort);
                                             },
                                             direction);
    }
}
