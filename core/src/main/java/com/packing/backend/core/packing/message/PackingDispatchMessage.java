package com.packing.backend.core.packing.message;

import com.packing.backend.domain.packing.PackingJobId;

public record PackingDispatchMessage(int messageVersion, PackingJobId jobId) {

    public static PackingDispatchMessage versionOne(PackingJobId jobId) {
        return new PackingDispatchMessage(1, jobId);
    }
}
