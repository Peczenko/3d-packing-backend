package com.packing.backend.core.packing;

import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import com.packing.backend.domain.packing.PackingJobStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@Transactional
public class PackingJobRecoveryService {

    private static final String DISPATCH_EXHAUSTED = "Dispatch exhausted Service Bus delivery attempts";

    private final PackingJobRepository jobs;
    private final Clock clock;

    public PackingJobRecoveryService(PackingJobRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    public void failExhaustedDispatch(PackingJobId id) {
        PackingJob job = jobs.findById(id)
                .orElseThrow(() -> PackingJobNotFoundException.byId(id));
        if (job.status() != PackingJobStatus.QUEUED) {
            return;
        }
        if (job.failBeforeStart(DISPATCH_EXHAUSTED, clock.instant())) {
            jobs.save(job);
        }
    }

    public void recoverResult(PackingJobId id, PackingJobArtifactStore.ResultArtifact result) {
        PackingJob job = jobs.findById(id)
                .orElseThrow(() -> PackingJobNotFoundException.byId(id));
        if (job.status() != PackingJobStatus.RUNNING) {
            return;
        }
        if (job.succeed(result.fileName(), result.contentType(), result.sizeBytes(), result.checksum(),
                result.engineVersion(), result.engineChecksum(), clock.instant())) {
            jobs.save(job);
        }
    }
}
