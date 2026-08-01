package com.packing.backend.api.packing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreatePackingJobRequest(
        @Min(1) @Max(7200) Long maxRuntimeSeconds,
        @JsonSetter(nulls = Nulls.FAIL) @NotNull JsonNode spec) {

    public long effectiveMaxRuntimeSeconds() {
        return maxRuntimeSeconds == null ? 60 : maxRuntimeSeconds;
    }
}
