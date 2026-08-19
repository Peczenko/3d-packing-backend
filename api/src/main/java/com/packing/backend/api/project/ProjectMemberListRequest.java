package com.packing.backend.api.project;

import com.packing.backend.api.shared.query.CollectionQueryParameters;
import com.packing.backend.core.project.ProjectMemberListCriteria;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.project.ProjectPermission;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.Set;

public record ProjectMemberListRequest(
        @Schema(description = "Zero-based page index.", defaultValue = "0") @Min(0) Integer page,
        @Schema(description = "Number of items per page.", defaultValue = "20") @Min(1) @Max(PageRequest.MAX_SIZE) Integer size,
        @Schema(description = "Case-insensitive literal substring matched against username or display name.") @Size(min = 3, max = 100) String search,
        @Schema(description = "Member permissions to include. Repeat to match any supplied value.") Set<ProjectPermission> permission,
        @Schema(description = "Inclusive lower bound for the membership grant timestamp.") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime addedFrom,
        @Schema(description = "Exclusive upper bound for the membership grant timestamp.") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime addedBefore,
        @Schema(description = "Field used to sort the result.", defaultValue = "addedAt",
                allowableValues = {
                        "username", "displayName", "permission", "addedAt" })
        @Pattern(regexp = "username|displayName|permission|addedAt") String sort,
        @Schema(description = "Sort direction. Defaults to ASC.") SortDirection direction) {

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
