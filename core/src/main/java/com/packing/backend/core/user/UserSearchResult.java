package com.packing.backend.core.user;

import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserStatus;

public record UserSearchResult(UserId id, String username, String displayName, UserStatus status) {
}
