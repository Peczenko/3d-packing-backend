package com.packing.backend.core.packing.port.out;

import com.packing.backend.domain.packing.PackingItem;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PreviewModel;

import java.util.List;

public interface PackingPreviewRenderer {

    List<PreviewModel> render(PackingJobId jobId, List<PackingItem> items);
}
