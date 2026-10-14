package com.packing.backend.core.packing;

import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@Transactional
@RequiredArgsConstructor
public class PackingJobRecoveryService {

    private final PackingJobRepository jobs;
    private final DomainEventPublisher events;
    private final Clock                clock;

    public void failStalled(PackingJobId id, String reason) {
        PackingJob job = jobs.findById(id)
                             .orElseThrow(() -> PackingJobNotFoundException.byId(id));
        if (job.failStalled(reason, clock.instant())) {
            jobs.save(job);
            events.publishAll(job.pullDomainEvents());
        }
    }
}
