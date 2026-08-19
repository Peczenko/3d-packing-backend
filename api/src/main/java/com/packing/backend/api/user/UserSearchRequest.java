package com.packing.backend.api.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserSearchRequest(
        @NotBlank @Size(min = 3) String pattern,
        @Min(1) @Max(MAX_LIMIT) Integer limit) {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 20;

    public UserSearchRequest {
        pattern = pattern == null ? null : pattern.trim();
        limit = limit == null ? DEFAULT_LIMIT : limit;
    }
}
