package com.packing.backend.infra.packing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.packing.message.PackingDispatchMessage;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.message.PackingWorkerEvent;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackingContractCodecTest {

    private static final PackingJobId JOB_ID =
            new PackingJobId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final String ENGINE_CHECKSUM = "a".repeat(64);
    private static final String RESULT_CHECKSUM = "b".repeat(64);

    private final PackingContractCodec codec = new PackingContractCodec(new ObjectMapper());

    @Test
    void encodesAndDecodesTheRequestEnvelopeGoldenFixture() throws IOException {
        PackingRequestEnvelope envelope = PackingRequestEnvelope.versionOne(60, "{\"testField\":\"value\"}");

        assertThat(codec.encodeRequest(envelope)).isEqualTo(fixture("request-envelope.example.json").strip());
    }

    @Test
    void encodesAnOpaqueArraySpecAsAJsonNode() {
        PackingRequestEnvelope envelope = PackingRequestEnvelope.versionOne(60, "[\"first\",2]");

        assertThat(codec.encodeRequest(envelope))
                .isEqualTo("{\"requestVersion\":1,\"maxRuntimeSeconds\":60,\"spec\":[\"first\",2]}");
    }

    @ParameterizedTest
    @MethodSource("nonNullScalarSpecs")
    void encodesAnOpaqueNonNullScalarSpecAsAJsonNode(String specJson, String expectedJson) {
        PackingRequestEnvelope envelope = PackingRequestEnvelope.versionOne(60, specJson);

        assertThat(codec.encodeRequest(envelope)).isEqualTo(expectedJson);
    }

    @ParameterizedTest
    @MethodSource("invalidSpecs")
    void rejectsInvalidOrMissingRequestSpecs(String specJson) {
        PackingRequestEnvelope envelope = PackingRequestEnvelope.versionOne(60, specJson);

        assertThatThrownBy(() -> codec.encodeRequest(envelope))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void encodesAndDecodesTheDispatchMessageGoldenFixture() throws IOException {
        PackingDispatchMessage message = PackingDispatchMessage.versionOne(JOB_ID);

        assertThat(codec.decodeDispatch(fixture("dispatch-message.example.json"))).isEqualTo(message);
        assertThat(codec.encodeDispatch(message)).isEqualTo(fixture("dispatch-message.example.json").strip());
    }

    @Test
    void encodesAndDecodesTheStartedEventGoldenFixture() throws IOException {
        PackingWorkerEvent event = new PackingWorkerEvent.Started(
                1, JOB_ID, "packer 0.3.0", ENGINE_CHECKSUM);

        assertThat(codec.decodeWorkerEvent(fixture("started-event.example.json"))).isEqualTo(event);
        assertThat(codec.encodeWorkerEvent(event)).isEqualTo(fixture("started-event.example.json").strip());
    }

    @Test
    void encodesAndDecodesTheSucceededEventGoldenFixture() throws IOException {
        PackingWorkerEvent event = new PackingWorkerEvent.Succeeded(
                1, JOB_ID, "packer 0.3.0", ENGINE_CHECKSUM,
                "output.bin", "application/octet-stream", 12, RESULT_CHECKSUM);

        assertThat(codec.decodeWorkerEvent(fixture("succeeded-event.example.json"))).isEqualTo(event);
        assertThat(codec.encodeWorkerEvent(event)).isEqualTo(fixture("succeeded-event.example.json").strip());
    }

    @Test
    void encodesAndDecodesTheFailedEventGoldenFixture() throws IOException {
        PackingWorkerEvent event = new PackingWorkerEvent.Failed(
                1, JOB_ID, "packer 0.3.0", ENGINE_CHECKSUM, "packer exited with code 2");

        assertThat(codec.decodeWorkerEvent(fixture("failed-event.example.json"))).isEqualTo(event);
        assertThat(codec.encodeWorkerEvent(event)).isEqualTo(fixture("failed-event.example.json").strip());
    }

    @Test
    void rejectsAnUnknownMessageVersion() {
        assertThatThrownBy(() -> codec.decodeDispatch("{\"messageVersion\":2,\"jobId\":\"" + JOB_ID + "\"}"))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void rejectsTrailingJsonDocuments() throws IOException {
        assertThatThrownBy(() -> codec.decodeDispatch(fixture("dispatch-message.example.json") + " {}"))
                .isInstanceOf(DomainRuleViolationException.class);
        assertThatThrownBy(() -> codec.decodeWorkerEvent(fixture("started-event.example.json") + " {}"))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "1.0", "\"1\"", "true"})
    void rejectsUnknownOrNonIntegerWorkerEventVersions(String messageVersion) {
        String event = "{\"messageVersion\":" + messageVersion
                + ",\"eventType\":\"started\",\"jobId\":\"" + JOB_ID
                + "\",\"engineVersion\":\"packer 0.3.0\",\"engineChecksum\":\""
                + ENGINE_CHECKSUM + "\"}";

        assertThatThrownBy(() -> codec.decodeWorkerEvent(event))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void rejectsNonVersionOneValuesWhenEncoding() {
        assertThatThrownBy(() -> codec.encodeRequest(new PackingRequestEnvelope(2, 60, "{}")))
                .isInstanceOf(DomainRuleViolationException.class);
        assertThatThrownBy(() -> codec.encodeDispatch(new PackingDispatchMessage(2, JOB_ID)))
                .isInstanceOf(DomainRuleViolationException.class);
        assertThatThrownBy(() -> codec.encodeWorkerEvent(
                new PackingWorkerEvent.Started(2, JOB_ID, "packer 0.3.0", ENGINE_CHECKSUM)))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void rejectsAnUnknownEventType() {
        assertThatThrownBy(() -> codec.decodeWorkerEvent(
                "{\"messageVersion\":1,\"eventType\":\"cancelled\",\"jobId\":\"" + JOB_ID
                        + "\",\"engineVersion\":\"packer 0.3.0\",\"engineChecksum\":\""
                        + ENGINE_CHECKSUM + "\"}"))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    private String fixture(String name) throws IOException {
        return Files.readString(Path.of("..", "contracts", "packing", "v1", name));
    }

    private static Stream<Arguments> nonNullScalarSpecs() {
        return Stream.of(
                Arguments.of("true", "{\"requestVersion\":1,\"maxRuntimeSeconds\":60,\"spec\":true}"),
                Arguments.of("42", "{\"requestVersion\":1,\"maxRuntimeSeconds\":60,\"spec\":42}"),
                Arguments.of("\"value\"", "{\"requestVersion\":1,\"maxRuntimeSeconds\":60,\"spec\":\"value\"}"));
    }

    private static Stream<Arguments> invalidSpecs() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of(" \t "),
                Arguments.of("{"),
                Arguments.of("{} {}"),
                Arguments.of("null"));
    }
}
