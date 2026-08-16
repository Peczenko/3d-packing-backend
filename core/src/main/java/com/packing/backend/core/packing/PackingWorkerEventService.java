package com.packing.backend.core.packing;

import com.packing.backend.core.packing.message.PackingWorkerEvent;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@Transactional
public class PackingWorkerEventService {

    private final PackingJobRepository jobs;
    private final Clock                clock;

    public PackingWorkerEventService(PackingJobRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

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
        }
    }
}
