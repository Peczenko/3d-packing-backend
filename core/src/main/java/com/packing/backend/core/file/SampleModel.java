package com.packing.backend.core.file;

import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.ModelFormat;

public record SampleModel(String id,
        String name,
        String description,
        FileName filename,
        long sizeBytes,
        Checksum checksum,
        ContentSource content) {

    public ModelFormat format() {
        return filename.format();
    }
}
