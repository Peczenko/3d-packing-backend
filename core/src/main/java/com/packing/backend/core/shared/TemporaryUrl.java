package com.packing.backend.core.shared;

import java.net.URI;
import java.time.Instant;

public record TemporaryUrl(URI url, Instant expiresAt) {
}
