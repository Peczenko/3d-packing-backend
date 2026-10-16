package com.packing.backend.core.packing.message;

import com.packing.backend.domain.packing.PackingInput;
import com.packing.backend.domain.packing.PackingItem;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingParameters;

import java.util.List;
import java.util.Objects;

public record PackingRequestEnvelope(int requestVersion,
        long maxRuntimeSeconds,
        int inputSchemaVersion,
        List<PackingItem> items,
        PackingParameters parameters) {

    public static final int VERSION = 3;

    public PackingRequestEnvelope {
        items = List.copyOf(items);
        Objects.requireNonNull(parameters, "parameters");
    }

    public static PackingRequestEnvelope forJob(PackingJob job) {
        PackingInput input = job.input();
        if (input == null) {
            throw new IllegalStateException("Packing job " + job.id() + " predates structured input and cannot be dispatched");
        }
        return new PackingRequestEnvelope(VERSION,
                                          job.maxRuntimeSeconds(),
                                          input.schemaVersion(),
                                          input.items(),
                                          input.parameters());
    }
}
