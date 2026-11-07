package com.packing.backend.api.file;

import com.packing.backend.core.file.SampleModel;
import com.packing.backend.domain.file.ModelFormat;

public record SampleModelResponse(
        String id,
        String name,
        String description,
        String filename,
        ModelFormat format,
        long sizeBytes) {

    public static SampleModelResponse from(SampleModel sample) {
        return new SampleModelResponse(sample.id(),
                                       sample.name(),
                                       sample.description(),
                                       sample.filename()
                                             .value(),
                                       sample.format(),
                                       sample.sizeBytes());
    }
}
