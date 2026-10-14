package com.packing.backend.infra.packing.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineFailureDetails;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineResult;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineStarted;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.RunEngineInput;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PackingEngineContractTest {

    private static final Path   EXAMPLES = Path.of("..", "contracts", "packing", "v" + PackingEngineContract.VERSION);
    private static final String CHECKSUM = "a".repeat(64);

    private final JacksonJsonPayloadConverter converter = new JacksonJsonPayloadConverter();
    private final ObjectMapper                json      = new ObjectMapper();

    @Test
    void runEngineInputMatchesItsExample() throws IOException {
        assertRoundTrip("run-engine-input.example.json",
                        RunEngineInput.class,
                        new RunEngineInput(2, "00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void engineStartedSignalMatchesItsExample() throws IOException {
        assertRoundTrip("engine-started-signal.example.json",
                        EngineStarted.class,
                        new EngineStarted(2, "packer 0.3.0", CHECKSUM));
    }

    @Test
    void engineResultMatchesItsExample() throws IOException {
        assertRoundTrip("run-engine-result.example.json",
                        EngineResult.class,
                        new EngineResult(2,
                                         "packer 0.3.0",
                                         CHECKSUM,
                                         "output.bin",
                                         "application/octet-stream",
                                         12,
                                         "b".repeat(64)));
    }

    @Test
    void engineFailureDetailsMatchItsExample() throws IOException {
        assertRoundTrip("engine-failure-details.example.json",
                        EngineFailureDetails.class,
                        new EngineFailureDetails(2, "packer 0.3.0", CHECKSUM, "packer exited with code 2"));
    }

    private <T> void assertRoundTrip(String example, Class<T> type, T expected) throws IOException {
        String wire = Files.readString(EXAMPLES.resolve(example));
        Payload payload = Payload.newBuilder()
                                 .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                                 .setData(ByteString.copyFromUtf8(wire))
                                 .build();

        assertThat(converter.fromData(payload, type, type)).isEqualTo(expected);
        String encoded = converter.toData(expected)
                                  .orElseThrow()
                                  .getData()
                                  .toStringUtf8();
        assertThat(json.readTree(encoded)).isEqualTo(json.readTree(wire));
    }
}
