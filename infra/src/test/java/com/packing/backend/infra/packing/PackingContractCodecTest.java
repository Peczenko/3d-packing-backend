package com.packing.backend.infra.packing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.packing.PackingItem;
import com.packing.backend.domain.packing.PackingParameters;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackingContractCodecTest {

    private final PackingContractCodec codec = new PackingContractCodec(new ObjectMapper());

    @Test
    void encodesTheRequestEnvelopeGoldenFixture() throws IOException {
        assertThat(codec.encodeRequest(envelope(PackingRequestEnvelope.VERSION, "{}")))
                                                                                       .isEqualTo(fixture("request-envelope.example.json").strip());
    }

    @Test
    void embedsTheParametersAsJson() {
        assertThat(codec.encodeRequest(envelope(PackingRequestEnvelope.VERSION, "{\"seed\":7,\"volume\":{\"widthMm\":220}}")))
                                                                                                                              .endsWith("\"parameters\":{\"seed\":7,\"volume\":{\"widthMm\":220}}}");
    }

    @ParameterizedTest
    @ValueSource(strings = { "[]", "42", "null", "{", "{} {}" })
    void rejectsParametersThatAreNotOneJsonObject(String parameters) {
        assertThatThrownBy(() -> codec.encodeRequest(envelope(PackingRequestEnvelope.VERSION, parameters)))
                                                                                                           .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void rejectsAnyOtherRequestVersion() {
        assertThatThrownBy(() -> codec.encodeRequest(envelope(1, "{}"))).isInstanceOf(DomainRuleViolationException.class);
    }

    static PackingRequestEnvelope envelope(int version, String parameters) {
        FileId fileId = new FileId(UUID.fromString("0b6c3c1e-4c4f-4a39-9b5e-2b8c2e6b1f00"));
        PackingItem bunny = new PackingItem(fileId,
                                            new FileName("bunny.obj"),
                                            ModelFormat.OBJ,
                                            new Checksum("c".repeat(64)),
                                            StorageKey.forFile(fileId),
                                            3);
        return new PackingRequestEnvelope(version, 120, 1, List.of(bunny), new PackingParameters(parameters));
    }

    private String fixture(String name) throws IOException {
        return Files.readString(Path.of("..", "contracts", "packing", "v" + PackingRequestEnvelope.VERSION, name));
    }
}
