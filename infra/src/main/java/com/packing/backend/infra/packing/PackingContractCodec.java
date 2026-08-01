package com.packing.backend.infra.packing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.packing.backend.core.packing.message.PackingDispatchMessage;
import com.packing.backend.core.packing.message.PackingRequestEnvelope;
import com.packing.backend.core.packing.message.PackingWorkerEvent;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.UUID;

public final class PackingContractCodec {

    private static final int VERSION_ONE = 1;

    private final ObjectMapper objectMapper;

    public PackingContractCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encodeRequest(PackingRequestEnvelope envelope) {
        requireVersion(envelope.requestVersion(), "request");
        ObjectNode request = objectMapper.createObjectNode();
        request.put("requestVersion", envelope.requestVersion());
        request.put("maxRuntimeSeconds", envelope.maxRuntimeSeconds());
        request.set("spec", readTree(envelope.specJson()));
        return write(request);
    }

    public String encodeDispatch(PackingDispatchMessage message) {
        requireVersion(message.messageVersion(), "message");
        ObjectNode dispatch = objectMapper.createObjectNode();
        dispatch.put("messageVersion", message.messageVersion());
        dispatch.put("jobId", message.jobId().toString());
        return write(dispatch);
    }

    public PackingDispatchMessage decodeDispatch(String json) {
        JsonNode dispatch = readObject(json);
        requireVersion(dispatch);
        return new PackingDispatchMessage(VERSION_ONE, jobId(dispatch));
    }

    public String encodeWorkerEvent(PackingWorkerEvent event) {
        requireVersion(event.messageVersion(), "message");
        ObjectNode message = objectMapper.createObjectNode();
        message.put("messageVersion", event.messageVersion());
        message.put("eventType", eventType(event));
        message.put("jobId", event.jobId().toString());
        message.put("engineVersion", event.engineVersion());
        message.put("engineChecksum", event.engineChecksum());
        switch (event) {
            case PackingWorkerEvent.Started ignored -> {
            }
            case PackingWorkerEvent.Succeeded succeeded -> {
                message.put("resultFileName", succeeded.resultFileName());
                message.put("resultContentType", succeeded.resultContentType());
                message.put("resultSizeBytes", succeeded.resultSizeBytes());
                message.put("resultChecksum", succeeded.resultChecksum());
            }
            case PackingWorkerEvent.Failed failed -> message.put("reason", failed.reason());
        }
        return write(message);
    }

    public PackingWorkerEvent decodeWorkerEvent(String json) {
        JsonNode event = readObject(json);
        requireVersion(event);
        PackingJobId jobId = jobId(event);
        String engineVersion = requiredText(event, "engineVersion");
        String engineChecksum = requiredText(event, "engineChecksum");
        return switch (requiredText(event, "eventType")) {
            case "started" -> new PackingWorkerEvent.Started(
                    VERSION_ONE, jobId, engineVersion, engineChecksum);
            case "succeeded" -> new PackingWorkerEvent.Succeeded(
                    VERSION_ONE, jobId, engineVersion, engineChecksum,
                    requiredText(event, "resultFileName"),
                    requiredText(event, "resultContentType"),
                    requiredLong(event, "resultSizeBytes"),
                    requiredText(event, "resultChecksum"));
            case "failed" -> new PackingWorkerEvent.Failed(
                    VERSION_ONE, jobId, engineVersion, engineChecksum,
                    requiredText(event, "reason"));
            default -> throw new DomainRuleViolationException("Unknown packing worker event type");
        };
    }

    private String eventType(PackingWorkerEvent event) {
        return switch (event) {
            case PackingWorkerEvent.Started ignored -> "started";
            case PackingWorkerEvent.Succeeded ignored -> "succeeded";
            case PackingWorkerEvent.Failed ignored -> "failed";
        };
    }

    private void requireVersion(JsonNode message) {
        JsonNode version = message.get("messageVersion");
        if (version == null || !version.isInt() || version.intValue() != VERSION_ONE) {
            throw new DomainRuleViolationException("Unsupported packing message version");
        }
    }

    private void requireVersion(int version, String contract) {
        if (version != VERSION_ONE) {
            throw new DomainRuleViolationException("Unsupported packing " + contract + " version");
        }
    }

    private PackingJobId jobId(JsonNode message) {
        try {
            return new PackingJobId(UUID.fromString(requiredText(message, "jobId")));
        } catch (IllegalArgumentException e) {
            throw new DomainRuleViolationException("Packing message jobId must be a UUID");
        }
    }

    private String requiredText(JsonNode message, String field) {
        JsonNode value = message.get(field);
        if (value == null || !value.isTextual()) {
            throw new DomainRuleViolationException("Packing message " + field + " must be a string");
        }
        return value.textValue();
    }

    private long requiredLong(JsonNode message, String field) {
        JsonNode value = message.get(field);
        if (value == null || !(value.isLong() || value.isInt())) {
            throw new DomainRuleViolationException("Packing message " + field + " must be an integer");
        }
        return value.longValue();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new DomainRuleViolationException("Packing message must be valid JSON");
        }
    }

    private JsonNode readObject(String json) {
        JsonNode value = readTree(json);
        if (value == null || !value.isObject()) {
            throw new DomainRuleViolationException("Packing message must be a JSON object");
        }
        return value;
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode packing contract", e);
        }
    }
}
