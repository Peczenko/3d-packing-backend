package com.packing.backend.api.file;

import com.packing.backend.api.shared.query.CollectionQueryParameters;
import com.packing.backend.core.file.FileListCriteria;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.file.ModelFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.Set;

public record FileListRequest(
        @Schema(description = "Zero-based page index.", defaultValue = "0") @Min(0) Integer page,
        @Schema(description = "Number of items per page.", defaultValue = "20") @Min(1) @Max(PageRequest.MAX_SIZE) Integer size,
        @Schema(description = "Case-insensitive literal substring matched against the original filename.") @Size(min = 3, max = 100) String search,
        @Schema(description = "Model formats to include. Repeat to match any supplied value.") Set<ModelFormat> format,
        @Schema(description = "Inclusive lower bound for the creation timestamp.") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
        @Schema(description = "Exclusive upper bound for the creation timestamp.") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdBefore,
        @Schema(description = "Field used to sort the result.", defaultValue = "createdAt",
                allowableValues = {
                        "filename", "format", "sizeBytes", "createdAt" })
        @Pattern(regexp = "filename|format|sizeBytes|createdAt") String sort,
        @Schema(description = "Sort direction. Defaults to ASC when sort is supplied; otherwise DESC.") SortDirection direction) {

    public FileListRequest {
        boolean customSort = sort != null;
        page = CollectionQueryParameters.page(page);
        size = CollectionQueryParameters.size(size);
        search = CollectionQueryParameters.search(search);
        format = CollectionQueryParameters.values(format);
        sort = sort == null ? "createdAt" : sort;
        direction = CollectionQueryParameters.direction(customSort, direction, SortDirection.DESC);
    }

    @AssertTrue(message = "createdFrom must be before createdBefore")
    public boolean hasValidCreatedAtRange() {
        return CollectionQueryParameters.validRange(createdFrom, createdBefore);
    }

    public FileListCriteria toCriteria() {
        return new FileListCriteria(
                                    new PageRequest(page, size),
                                    search,
                                    format,
                                    CollectionQueryParameters.range(createdFrom, createdBefore),
                                    switch (sort) {
                                        case "filename" -> FileListCriteria.SortField.FILENAME;
                                        case "format" -> FileListCriteria.SortField.FORMAT;
                                        case "sizeBytes" -> FileListCriteria.SortField.SIZE_BYTES;
                                        case "createdAt" -> FileListCriteria.SortField.CREATED_AT;
                                        default -> throw new IllegalStateException("Unexpected sort: " + sort);
                                    },
                                    direction);
    }
}
