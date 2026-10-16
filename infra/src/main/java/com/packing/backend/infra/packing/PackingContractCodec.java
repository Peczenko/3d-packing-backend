package com.packing.backend.infra.packing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.springframework.stereotype.Component;

@Component
public final class PackingContractCodec {

    private static final int VERSION_ONE = 1;

    private final ObjectMapper objectMapper;
    private final ObjectReader strictTreeReader;

    public PackingContractCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.strictTreeReader = objectMapper.reader()
                                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public String encodeRequest(PackingRequestEnvelope envelope) {
        if (envelope.requestVersion() != VERSION_ONE) {
            throw new DomainRuleViolationException("Unsupported packing request version");
        }
        ObjectNode request = objectMapper.createObjectNode();
        request.put("requestVersion", envelope.requestVersion());
        request.put("maxRuntimeSeconds", envelope.maxRuntimeSeconds());
        request.set("spec", readSpec(envelope.specJson()));
        return write(request);
    }

    private JsonNode readSpec(String json) {
        if (json == null || json.isBlank()) {
            throw new DomainRuleViolationException("Packing request spec must be valid JSON");
        }
        JsonNode spec;
        try {
            spec = strictTreeReader.readTree(json);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new DomainRuleViolationException("Packing request spec must be valid JSON");
        }
        if (spec == null || spec.isMissingNode() || spec.isNull()) {
            throw new DomainRuleViolationException("Packing request spec must not be null");
        }
        return spec;
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode packing contract", e);
        }
    }
}
