package com.packing.backend.core.packing;

import com.packing.backend.core.packing.message.PackingWorkerEvent;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@Transactional
@RequiredArgsConstructor
public class PackingWorkerEventService {

    private final PackingJobRepository jobs;
    private final DomainEventPublisher events;
    private final Clock                clock;

    public void apply(PackingWorkerEvent event) {
        PackingJob job = jobs.findById(event.jobId())
                             .orElseThrow(() -> PackingJobNotFoundException.byId(event.jobId()));
        boolean changed = switch (event) {
            case PackingWorkerEvent.Started started -> job.markRunning(
                                                                       started.engineVersion(),
                                                                       started.engineChecksum(),
                                                                       clock.instant());
            case PackingWorkerEvent.Succeeded succeeded -> job.succeed(
                                                                       succeeded.resultFileName(),
                                                                       succeeded.resultContentType(),
                                                                       succeeded.resultSizeBytes(),
                                                                       succeeded.resultChecksum(),
                                                                       succeeded.engineVersion(),
                                                                       succeeded.engineChecksum(),
                                                                       clock.instant());
            case PackingWorkerEvent.Failed failed -> job.fail(
                                                              failed.reason(),
                                                              failed.engineVersion(),
                                                              failed.engineChecksum(),
                                                              clock.instant());
        };
        if (changed) {
            jobs.save(job);
            events.publishAll(job.pullDomainEvents());
        }
    }
}
