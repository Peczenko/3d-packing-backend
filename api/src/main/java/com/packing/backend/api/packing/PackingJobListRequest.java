package com.packing.backend.api.packing;

import com.packing.backend.api.shared.query.CollectionQueryParameters;
import com.packing.backend.core.packing.PackingJobListCriteria;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.packing.PackingJobStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.Set;

public record PackingJobListRequest(
        @Min(0) Integer page,
        @Min(1) @Max(PageRequest.MAX_SIZE) Integer size,
        @Size(min = 3, max = 100) String search,
        Set<PackingJobStatus> status,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdBefore,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startedFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startedBefore,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime finishedFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime finishedBefore,
        @Pattern(regexp = "status|maxRuntimeSeconds|engineVersion|createdAt|startedAt|finishedAt|resultFileName|resultSizeBytes") String sort,
        SortDirection direction) {

    public PackingJobListRequest {
        boolean customSort = sort != null;
        page = CollectionQueryParameters.page(page);
        size = CollectionQueryParameters.size(size);
        search = CollectionQueryParameters.search(search);
        status = CollectionQueryParameters.values(status);
        sort = sort == null ? "createdAt" : sort;
        direction = CollectionQueryParameters.direction(customSort, direction, SortDirection.DESC);
    }

    @AssertTrue(message = "createdFrom must be before createdBefore")
    public boolean hasValidCreatedAtRange() {
        return CollectionQueryParameters.validRange(createdFrom, createdBefore);
    }

    @AssertTrue(message = "startedFrom must be before startedBefore")
    public boolean hasValidStartedAtRange() {
        return CollectionQueryParameters.validRange(startedFrom, startedBefore);
    }

    @AssertTrue(message = "finishedFrom must be before finishedBefore")
    public boolean hasValidFinishedAtRange() {
        return CollectionQueryParameters.validRange(finishedFrom, finishedBefore);
    }

    public PackingJobListCriteria toCriteria() {
        return new PackingJobListCriteria(
                                          new PageRequest(page, size),
                                          search,
                                          status,
                                          CollectionQueryParameters.range(createdFrom, createdBefore),
                                          CollectionQueryParameters.range(startedFrom, startedBefore),
                                          CollectionQueryParameters.range(finishedFrom, finishedBefore),
                                          switch (sort) {
                                              case "status" -> PackingJobListCriteria.SortField.STATUS;
                                              case "maxRuntimeSeconds" -> PackingJobListCriteria.SortField.MAX_RUNTIME_SECONDS;
                                              case "engineVersion" -> PackingJobListCriteria.SortField.ENGINE_VERSION;
                                              case "createdAt" -> PackingJobListCriteria.SortField.CREATED_AT;
                                              case "startedAt" -> PackingJobListCriteria.SortField.STARTED_AT;
                                              case "finishedAt" -> PackingJobListCriteria.SortField.FINISHED_AT;
                                              case "resultFileName" -> PackingJobListCriteria.SortField.RESULT_FILE_NAME;
                                              case "resultSizeBytes" -> PackingJobListCriteria.SortField.RESULT_SIZE_BYTES;
                                              default -> throw new IllegalStateException("Unexpected sort: " + sort);
                                          },
                                          direction);
    }
}
