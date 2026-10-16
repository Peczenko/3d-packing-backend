package com.packing.backend.infra.packing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackingContractCodecTest {

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
    void rejectsTrailingJsonDocumentsInTheSpec() {
        PackingRequestEnvelope envelope = PackingRequestEnvelope.versionOne(60, "{\"testField\":true} {}");

        assertThatThrownBy(() -> codec.encodeRequest(envelope))
                                                               .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void rejectsNonVersionOneValuesWhenEncoding() {
        assertThatThrownBy(() -> codec.encodeRequest(new PackingRequestEnvelope(2, 60, "{}")))
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
