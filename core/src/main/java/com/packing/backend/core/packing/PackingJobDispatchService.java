package com.packing.backend.core.packing;

import com.packing.backend.core.packing.message.PackingDispatchMessage;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.port.out.PackingDispatchSender;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import com.packing.backend.domain.packing.PackingJobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RequiredArgsConstructor
public class PackingJobDispatchService {

    private final PackingJobRepository jobs;
    private final PackingJobArtifactStore artifacts;
    private final PackingDispatchSender dispatch;
    private final Clock clock;

    public void dispatch(PackingJobId id) {
        PackingJob job = jobs.findById(id).orElseThrow(() -> PackingJobNotFoundException.byId(id));
        if (job.dispatchedAt() != null || job.status() != PackingJobStatus.QUEUED) {
            return;
        }

        artifacts.writeRequestIfAbsent(id, PackingRequestEnvelope.versionOne(
                job.maxRuntimeSeconds(), job.specJson()));
        dispatch.send(PackingDispatchMessage.versionOne(id));
        if (job.markDispatched(clock.instant())) {
            jobs.save(job);
        }
    }
}
