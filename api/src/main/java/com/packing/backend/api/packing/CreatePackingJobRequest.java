package com.packing.backend.api.packing;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreatePackingJobRequest(
        @Min(1) @Max(7200) @Schema(description = "Solver time budget; defaults to 60") Long maxRuntimeSeconds,
        @NotNull @Schema(description = "Packing problem definition passed to the solver") JsonNode spec) {

    public CreatePackingJobRequest {
        if (spec != null && spec.isNull()) {
            spec = null;
        }
    }

    public long effectiveMaxRuntimeSeconds() {
        return maxRuntimeSeconds == null ? 60 : maxRuntimeSeconds;
    }
}
