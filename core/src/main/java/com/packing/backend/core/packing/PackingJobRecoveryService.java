package com.packing.backend.core.packing;

import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import com.packing.backend.domain.packing.PackingJobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@Transactional
@RequiredArgsConstructor
public class PackingJobRecoveryService {

    private static final String DISPATCH_EXHAUSTED = "Dispatch exhausted Service Bus delivery attempts";
    private static final String DISPATCH_EXPIRED   = "Dispatch expired before the job started";

    private final PackingJobRepository jobs;
    private final DomainEventPublisher events;
    private final Clock                clock;

    public enum DispatchStall {
        DELIVERY_EXHAUSTED,
        EXPIRED
    }

    public boolean resolveStalledDispatch(PackingJobId id, DispatchStall stall) {
        PackingJob job = load(id);
        if (stall == DispatchStall.EXPIRED && job.status() == PackingJobStatus.RUNNING) {
            return false;
        }
        if (job.failStalled(reasonFor(stall), clock.instant())) {
            saveAndPublish(job);
        }
        return true;
    }

    private static String reasonFor(DispatchStall stall) {
        return switch (stall) {
            case DELIVERY_EXHAUSTED -> DISPATCH_EXHAUSTED;
            case EXPIRED -> DISPATCH_EXPIRED;
        };
    }

    public void recoverResult(PackingJobId id, PackingJobArtifactStore.ResultArtifact result) {
        PackingJob job = load(id);
        if (job.status() != PackingJobStatus.RUNNING) {
            return;
        }
        succeed(job, result);
    }

    public void recoverStalledResult(PackingJobId id, PackingJobArtifactStore.ResultArtifact result) {
        succeed(load(id), result);
    }

    private void succeed(PackingJob job, PackingJobArtifactStore.ResultArtifact result) {
        if (job.succeed(result.fileName(),
                        result.contentType(),
                        result.sizeBytes(),
                        result.checksum(),
                        result.engineVersion(),
                        result.engineChecksum(),
                        clock.instant())) {
            saveAndPublish(job);
        }
    }

    private void saveAndPublish(PackingJob job) {
        jobs.save(job);
        events.publishAll(job.pullDomainEvents());
    }

    private PackingJob load(PackingJobId id) {
        return jobs.findById(id)
                   .orElseThrow(() -> PackingJobNotFoundException.byId(id));
    }
}
