package com.packing.backend.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.domain.packing.PackingJob;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceBusEmulatorConfigTest {

    private static final Duration LONGEST_JOB = Duration.ofSeconds(PackingJob.MAX_RUNTIME_SECONDS);

    @Test
    void noQueueSilentlyDropsAMessageThatCouldStillBelongToALiveJob() throws IOException {
        List<String> queueNames = new ArrayList<>();
        List<String> offenders = new ArrayList<>();

        for (JsonNode namespace : emulatorConfig().required("UserConfig").required("Namespaces")) {
            for (JsonNode queue : namespace.required("Queues")) {
                String name = queue.required("Name").asText();
                JsonNode properties = queue.required("Properties");
                JsonNode timeToLive = properties.get("DefaultMessageTimeToLive");

                if (!outlivesTheLongestJob(timeToLive) && !properties.path("DeadLetteringOnMessageExpiration").asBoolean(false)) {
                    offenders.add("%s (DefaultMessageTimeToLive=%s, DeadLetteringOnMessageExpiration=%s)"
                            .formatted(name,
                                    timeToLive == null ? "<absent>" : timeToLive.asText(),
                                    properties.path("DeadLetteringOnMessageExpiration").asBoolean(false)));
                }
                queueNames.add(name);
            }
        }

        assertThat(queueNames)
                .as("the emulator config must still declare the pipeline's queues")
                .contains("packing-dispatch", "packing-results");
        assertThat(offenders)
                .as("a queue whose TTL can elapse while a job is still running must dead-letter on expiry, "
                        + "otherwise Service Bus drops the message with nothing left to observe the loss")
                .isEmpty();
    }

    // A queue with no DefaultMessageTimeToLive is NOT unbounded: the emulator substitutes PT1H and echoes
    // that back at startup, so an absent key is treated here exactly like the one-hour value it becomes.
    private static boolean outlivesTheLongestJob(JsonNode timeToLive) {
        return timeToLive != null && Duration.parse(timeToLive.asText()).compareTo(LONGEST_JOB) > 0;
    }

    private static JsonNode emulatorConfig() throws IOException {
        String repositoryRoot = System.getProperty("packing.repositoryRoot");
        assertThat(repositoryRoot)
                .as("packing.repositoryRoot must be set by the test task; config/ sits above every module")
                .isNotNull();
        Path config = Path.of(repositoryRoot, "config", "servicebus", "config.json");
        assertThat(config).exists();
        return new ObjectMapper().readTree(Files.readString(config));
    }
}
