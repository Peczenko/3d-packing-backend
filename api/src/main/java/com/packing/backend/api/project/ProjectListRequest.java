package com.packing.backend.api.project;

import com.packing.backend.api.shared.query.CollectionQueryParameters;
import com.packing.backend.core.project.ProjectListCriteria;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.Set;

public record ProjectListRequest(
        @Schema(description = "Zero-based page index.", defaultValue = "0") @Min(0) Integer page,
        @Schema(description = "Number of items per page.", defaultValue = "20") @Min(1) @Max(PageRequest.MAX_SIZE) Integer size,
        @Schema(description = "Case-insensitive literal substring matched against the project name.") @Size(min = 3, max = 100) String search,
        @Parameter(description = "Project statuses to include. Repeat to match any supplied value.",
                   array = @ArraySchema(schema = @Schema(type = "string", allowableValues = {
                           "ACTIVE", "DISABLED" }))) Set<ProjectStatus> status,
        @Schema(description = "Caller permissions to include. Repeat to match any supplied value.") Set<ProjectPermission> permission,
        @Schema(description = "Inclusive lower bound for the creation timestamp.") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
        @Schema(description = "Exclusive upper bound for the creation timestamp.") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdBefore,
        @Schema(description = "Inclusive lower bound for the last-update timestamp.") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime updatedFrom,
        @Schema(description = "Exclusive upper bound for the last-update timestamp.") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime updatedBefore,
        @Schema(description = "Field used to sort the result.", defaultValue = "createdAt",
                allowableValues = {
                        "name", "status", "permission", "memberCount", "createdAt", "updatedAt" })
        @Pattern(regexp = "name|status|permission|memberCount|createdAt|updatedAt") String sort,
        @Schema(description = "Sort direction. Defaults to ASC when sort is supplied; otherwise DESC.") SortDirection direction) {

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
