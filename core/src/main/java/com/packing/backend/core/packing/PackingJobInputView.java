package com.packing.backend.core.packing;

import com.packing.backend.domain.packing.PackingInput;

import java.util.List;
import java.util.UUID;

public record PackingJobInputView(int schemaVersion,
        long maxRuntimeSeconds,
        String parametersJson,
        List<Item> items) {

    public PackingJobInputView {
        items = List.copyOf(items);
    }

    public static PackingJobInputView of(PackingInput input, long maxRuntimeSeconds) {
        return new PackingJobInputView(input.schemaVersion(),
                                       maxRuntimeSeconds,
                                       input.parameters()
                                            .json(),
                                       input.items()
                                            .stream()
                                            .map(item -> new Item(item.fileId()
                                                                      .value(),
                                                                  item.fileName()
                                                                      .value(),
                                                                  item.copies()))
                                            .toList());
    }

    public record Item(UUID fileId, String fileName, int copies) {
    }
}
