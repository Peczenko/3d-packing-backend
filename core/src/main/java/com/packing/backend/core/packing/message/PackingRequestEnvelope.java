package com.packing.backend.core.packing.message;

public record PackingRequestEnvelope(int requestVersion,
                                     long maxRuntimeSeconds,
                                     String specJson) {

    public static PackingRequestEnvelope versionOne(long seconds, String specJson) {
        return new PackingRequestEnvelope(1, seconds, specJson);
    }
}
