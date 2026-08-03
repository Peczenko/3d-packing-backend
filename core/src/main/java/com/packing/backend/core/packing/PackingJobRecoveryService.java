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
        PackingJob job = load(id);
        if (job.failStalled(DISPATCH_EXHAUSTED, clock.instant())) {
            jobs.save(job);
        }
    }

    // A live job's result blob can race a worker that is still writing it, so a QUEUED job is left
    // for the worker's own started/succeeded events rather than completed from the blob.
    public void recoverResult(PackingJobId id, PackingJobArtifactStore.ResultArtifact result) {
        PackingJob job = load(id);
        if (job.status() != PackingJobStatus.RUNNING) {
            return;
        }
        succeed(job, result);
    }

    // A dead-lettered dispatch proves no worker still holds the message, so there is no race left
    // to guard against and any non-terminal job may be completed from its blob.
    public void recoverStalledResult(PackingJobId id, PackingJobArtifactStore.ResultArtifact result) {
        succeed(load(id), result);
    }

    private void succeed(PackingJob job, PackingJobArtifactStore.ResultArtifact result) {
        if (job.succeed(result.fileName(), result.contentType(), result.sizeBytes(), result.checksum(),
                result.engineVersion(), result.engineChecksum(), clock.instant())) {
            jobs.save(job);
        }
    }

    private PackingJob load(PackingJobId id) {
        return jobs.findById(id).orElseThrow(() -> PackingJobNotFoundException.byId(id));
    }
}
