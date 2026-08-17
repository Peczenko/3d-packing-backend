package com.packing.backend.api.user;

import com.packing.backend.core.user.UserSearchResult;
import com.packing.backend.domain.user.UserStatus;

import java.util.UUID;

public record UserSearchResponse(UUID id, String username, String displayName, UserStatus status) {

    public static UserSearchResponse from(UserSearchResult result) {
        return new UserSearchResponse(result.id()
                                            .value(),
                                      result.username(),
                                      result.displayName(),
                                      result.status());
    }
}
