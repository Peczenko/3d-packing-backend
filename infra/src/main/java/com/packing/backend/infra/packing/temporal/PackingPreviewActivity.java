package com.packing.backend.infra.packing.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PackingPreviewActivity {

    String TASK_QUEUE = "packing-preview";

    @ActivityMethod(name = "BuildPreview")
    void buildPreview(String jobId);
}
