package com.packing.backend.app;

import com.packing.backend.core.packing.PackingJobDispatchService;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.core.project.port.out.ProjectRepository;
import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.packing.PackingInputs;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.Username;
import com.packing.backend.infra.TestcontainersConfiguration;
import com.packing.backend.infra.packing.temporal.PackingEngineActivity;
import com.packing.backend.infra.packing.temporal.PackingEngineContract;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineResult;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineStarted;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.RunEngineInput;
import com.packing.backend.infra.packing.temporal.PackingJobWorkflow;
import io.temporal.activity.Activity;
import io.temporal.client.WorkflowClient;
import io.temporal.spring.boot.ActivityImpl;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@Import({ TestcontainersConfiguration.class, PackingTemporalWiringIT.FakeEngineConfiguration.class })
@SpringBootTest(properties = {
        "spring.temporal.test-server.enabled=true",
        "spring.temporal.start-workers=false",
        "spring.temporal.workers-auto-discovery.workflow-packages=com.packing.backend.infra.packing.temporal",
        "spring.temporal.workers-auto-discovery.register-activity-beans=true"
})
class PackingTemporalWiringIT {

    private static final String CHECKSUM = "a".repeat(64);

    @Autowired
    private WorkerFactory workerFactory;

    @Autowired
    private PackingJobDispatchService dispatcher;

    @Autowired
    private UserRepository users;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private PackingJobRepository jobs;

    @Autowired
    private RecordingArtifactStore artifacts;

    @Test
    void aDispatchedJobRunsThroughTheWorkflowToSucceeded() throws InterruptedException {
        assertThat(workerFactory.tryGetWorker(PackingEngineContract.STATE_TASK_QUEUE)).isNotNull();
        assertThat(workerFactory.isStarted()).isTrue();
        Instant now = Instant.now()
                             .truncatedTo(ChronoUnit.MICROS);
        User user = User.register(new FirebaseUid("uid-wiring"),
                                  new Email("wiring@example.com"),
                                  new Username("wiring"),
                                  "Wiring",
                                  now);
        users.save(user);
        Project project = Project.create(new ProjectName("Wiring"), user.id(), now);
        projects.save(project);
        PackingJob job = PackingJob.queue(PackingJobId.generate(),
                                          project.id(),
                                          user.id(),
                                          PackingInputs.single(),
                                          60,
                                          now);
        jobs.save(job);

        dispatcher.dispatch(job.id());

        PackingJob finished = awaitTerminal(job.id());
        assertThat(finished.status()).isEqualTo(PackingJobStatus.SUCCEEDED);
        assertThat(finished.dispatchedAt()).isNotNull();
        assertThat(finished.startedAt()).isNotNull();
        assertThat(finished.engineVersion()).isEqualTo("packer 0.3.0");
        assertThat(finished.resultFileName()).isEqualTo("output");
        assertThat(artifacts.requested).containsExactly(job.id());
    }

    private PackingJob awaitTerminal(PackingJobId id) throws InterruptedException {
        Instant deadline = Instant.now()
                                  .plus(Duration.ofSeconds(30));
        while (Instant.now()
                      .isBefore(deadline)) {
            PackingJob current = jobs.findById(id)
                                     .orElseThrow();
            if (current.status() == PackingJobStatus.SUCCEEDED || current.status() == PackingJobStatus.FAILED) {
                return current;
            }
            Thread.sleep(200);
        }
        return jobs.findById(id)
                   .orElseThrow();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeEngineConfiguration {

        @Bean
        FakeEngine fakeEngine(ObjectProvider<WorkflowClient> client) {
            return new FakeEngine(client);
        }

        @Bean
        @Primary
        RecordingArtifactStore recordingArtifactStore() {
            return new RecordingArtifactStore();
        }
    }

    @ActivityImpl(taskQueues = PackingEngineContract.ENGINE_TASK_QUEUE)
    static class FakeEngine implements PackingEngineActivity {

        private final ObjectProvider<WorkflowClient> client;

        FakeEngine(ObjectProvider<WorkflowClient> client) {
            this.client = client;
        }

        @Override
        public EngineResult runEngine(RunEngineInput input) {
            String workflowId = Activity.getExecutionContext()
                                        .getInfo()
                                        .getWorkflowId();
            client.getObject()
                  .newWorkflowStub(PackingJobWorkflow.class, workflowId)
                  .engineStarted(new EngineStarted(PackingEngineContract.VERSION, "packer 0.3.0", CHECKSUM));
            return new EngineResult(PackingEngineContract.VERSION,
                                    "packer 0.3.0",
                                    CHECKSUM,
                                    "output",
                                    "application/json",
                                    12,
                                    "b".repeat(64));
        }
    }

    static class RecordingArtifactStore implements PackingJobArtifactStore {

        private final List<PackingJobId> requested = new CopyOnWriteArrayList<>();

        @Override
        public void writeRequestIfAbsent(PackingJobId jobId, PackingRequestEnvelope envelope) {
            requested.add(jobId);
        }

        @Override
        public Optional<ResultArtifact> findResult(PackingJobId jobId) {
            return Optional.empty();
        }

        @Override
        public TemporaryUrl createResultDownloadUrl(PackingJobId jobId, Duration validity) {
            throw new UnsupportedOperationException();
        }
    }
}
