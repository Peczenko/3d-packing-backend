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
    private static final String DISPATCH_EXPIRED = "Dispatch expired before the job started";

    private final PackingJobRepository jobs;
    private final Clock clock;

    public PackingJobRecoveryService(PackingJobRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    public enum DispatchStall {
        DELIVERY_EXHAUSTED,
        EXPIRED
    }

    // Delivery exhaustion is the only stall that proves no worker still holds the dispatch: the
    // broker only exhausts a message it is about to redeliver, which needs a free lock. Expiry is
    // orthogonal to the lock and can fall an hour short of maxRuntimeSeconds, so a RUNNING job is
    // left for its worker and the caller is told not to discard the evidence yet.
    public boolean resolveStalledDispatch(PackingJobId id, DispatchStall stall) {
        PackingJob job = load(id);
        if (stall == DispatchStall.EXPIRED && job.status() == PackingJobStatus.RUNNING) {
            return false;
        }
        if (job.failStalled(reasonFor(stall), clock.instant())) {
            jobs.save(job);
        }
        return true;
    }

    private static String reasonFor(DispatchStall stall) {
        return switch (stall) {
            case DELIVERY_EXHAUSTED -> DISPATCH_EXHAUSTED;
            case EXPIRED -> DISPATCH_EXPIRED;
        };
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
