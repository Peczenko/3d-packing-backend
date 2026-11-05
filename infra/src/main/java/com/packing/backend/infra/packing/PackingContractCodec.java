package com.packing.backend.infra.packing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.domain.packing.PackingItem;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.springframework.stereotype.Component;

@Component
public final class PackingContractCodec {

    private final ObjectMapper objectMapper;
    private final ObjectReader strictTreeReader;

    public PackingContractCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.strictTreeReader = objectMapper.reader()
                                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public String encodeRequest(PackingRequestEnvelope envelope) {
        if (envelope.requestVersion() != PackingRequestEnvelope.VERSION) {
            throw new DomainRuleViolationException("Unsupported packing request version");
        }
        ObjectNode request = objectMapper.createObjectNode();
        request.put("requestVersion", envelope.requestVersion());
        request.put("maxRuntimeSeconds", envelope.maxRuntimeSeconds());
        request.put("inputSchemaVersion", envelope.inputSchemaVersion());
        ArrayNode items = request.putArray("items");
        for (PackingItem item : envelope.items()) {
            items.addObject()
                 .put("storageKey",
                      item.storageKey()
                          .value())
                 .put("fileName",
                      item.fileName()
                          .value())
                 .put("format",
                      item.format()
                          .name())
                 .put("checksum",
                      item.checksum()
                          .value())
                 .put("copies", item.copies());
        }
        request.set("parameters",
                    readParameters(envelope.parameters()
                                           .json()));
        return write(request);
    }

    private JsonNode readParameters(String json) {
        JsonNode parameters;
        try {
            parameters = strictTreeReader.readTree(json);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new DomainRuleViolationException("Packing request parameters must be a JSON object");
        }
        if (parameters == null || !parameters.isObject()) {
            throw new DomainRuleViolationException("Packing request parameters must be a JSON object");
        }
        return parameters;
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode packing contract", e);
        }
    }
}
