package com.packing.backend.core.notification;

import java.time.Instant;

public record ServerErrorReport(
        String traceId,
        Instant occurredAt,
        int status,
        String httpMethod,
        String path,
        String uriTemplate,
        String clientIp,
        String userAgent,
        String firebaseUid,
        String userEmail,
        String roles,
        Throwable cause) {
}
