package com.packing.backend.api.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Bean Validation here is a cheap first pass that produces per-field errors. The
 * authoritative rules live in the {@code Username} value object, which rejects anything
 * this misses.
 *
 * @param displayName null or blank clears the display name
 */
public record UpdateUserProfileRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @Size(max = 128) String displayName) {
}
