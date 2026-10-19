package com.packing.backend.api.packing;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.packing.backend.core.packing.PackingJobInputView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record PackingJobInputResponse(
        int schemaVersion,
        long maxRuntimeSeconds,
        @JsonRawValue @Schema(type = "object", description = "Engine options, as described by the input schema") String parameters,
        List<Item> items) {

    public static PackingJobInputResponse from(PackingJobInputView view) {
        return new PackingJobInputResponse(view.schemaVersion(),
                                           view.maxRuntimeSeconds(),
                                           view.parametersJson(),
                                           view.items()
                                               .stream()
                                               .map(item -> new Item(item.fileId(), item.fileName(), item.copies()))
                                               .toList());
    }

    public record Item(UUID fileId, String fileName, int copies) {
    }
}
