package com.packing.backend.api.packing;

import com.packing.backend.domain.packing.PackingPreview;
import com.packing.backend.domain.packing.PreviewModel;
import com.packing.backend.domain.packing.PreviewQuality;
import com.packing.backend.domain.packing.PreviewStatus;

import java.util.List;

public record PackingJobPreviewResponse(PreviewStatus status, List<Model> models) {

    public static PackingJobPreviewResponse from(PackingPreview preview) {
        return new PackingJobPreviewResponse(preview.status(), modelsOf(preview.models()));
    }

    static List<Model> modelsOf(List<PreviewModel> models) {
        return models.stream()
                     .map(model -> new Model(model.quality(), model.triangles(), model.sizeBytes()))
                     .toList();
    }

    public record Model(PreviewQuality quality, long triangles, long sizeBytes) {
    }
}
