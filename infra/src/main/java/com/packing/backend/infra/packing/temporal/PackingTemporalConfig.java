package com.packing.backend.infra.packing.temporal;

import com.packing.backend.core.packing.PackingJobDispatchService;
import com.packing.backend.core.packing.PackingJobRecoveryService;
import com.packing.backend.core.packing.PackingWorkerEventService;
import com.packing.backend.core.packing.port.out.PackingDispatchSender;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import io.temporal.client.WorkflowClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnExpression("${spring.temporal.test-server.enabled:false} || '${spring.temporal.connection.target:}'.length() > 0")
public class PackingTemporalConfig {

    @Bean
    PackingDispatchSender temporalPackingDispatchSender(WorkflowClient client, PackingJobRepository jobs) {
        return new TemporalPackingDispatchSender(client, jobs);
    }

    @Bean
    PackingJobDispatchService packingJobDispatchService(PackingJobRepository jobs,
                                                        PackingJobArtifactStore artifacts,
                                                        PackingDispatchSender dispatch,
                                                        Clock clock) {
        return new PackingJobDispatchService(jobs, artifacts, dispatch, clock);
    }

    @Bean
    PackingJobQueuedListener packingJobQueuedListener(PackingJobDispatchService dispatcher) {
        return new PackingJobQueuedListener(dispatcher);
    }

    @Bean
    PackingDispatchSweeper packingDispatchSweeper(PackingJobFinder finder, PackingJobDispatchService dispatcher) {
        return new PackingDispatchSweeper(finder, dispatcher);
    }

    @Bean
    PackingStateActivitiesImpl packingStateActivities(PackingWorkerEventService workerEvents,
                                                      PackingJobRecoveryService recovery) {
        return new PackingStateActivitiesImpl(workerEvents, recovery);
    }
}
