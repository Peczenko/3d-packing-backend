package com.packing.backend.api.packing;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreatePackingJobRequest(
        @Min(1) @Max(7200) Long maxRuntimeSeconds,
        @NotNull JsonNode spec) {

    public CreatePackingJobRequest {
        if (spec != null && spec.isNull()) {
            spec = null;
        }
    }

    public long effectiveMaxRuntimeSeconds() {
        return maxRuntimeSeconds == null ? 60 : maxRuntimeSeconds;
    }
}
