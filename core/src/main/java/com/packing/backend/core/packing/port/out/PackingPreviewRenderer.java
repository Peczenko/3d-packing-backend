package com.packing.backend.core.packing.port.out;

import com.packing.backend.domain.packing.PackingItem;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PreviewModel;

import java.util.List;

public interface PackingPreviewRenderer {

    Rendered render(PackingJobId jobId, List<PackingItem> items, String resultChecksum, int binCount);

    record Rendered(List<PreviewModel> models, List<List<PreviewModel>> bins) {
    }
}
