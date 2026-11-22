package com.packing.backend.core.packing;

import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.core.packing.port.out.PackingPreviewRenderer;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import com.packing.backend.domain.packing.PreviewStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PackingPreviewService {

    private final PackingJobRepository   jobs;
    private final PackingPreviewRenderer renderer;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void build(PackingJobId id) {
        PackingJob job = require(id);
        if (job.preview()
               .status() != PreviewStatus.PENDING) {
            return;
        }
        PackingPreviewRenderer.Rendered rendered = renderer.render(id,
                                                                   job.input()
                                                                      .items(),
                                                                   job.resultChecksum(),
                                                                   job.binCount());
        PackingJob current = require(id);
        if (current.completePreview(rendered.models(), rendered.bins())) {
            jobs.save(current);
        }
    }

    @Transactional
    public void fail(PackingJobId id) {
        PackingJob job = require(id);
        if (job.failPreview()) {
            jobs.save(job);
        }
    }

    private PackingJob require(PackingJobId id) {
        return jobs.findById(id)
                   .orElseThrow(() -> PackingJobNotFoundException.byId(id));
    }
}
