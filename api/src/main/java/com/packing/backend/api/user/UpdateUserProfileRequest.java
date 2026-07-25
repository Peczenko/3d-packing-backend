package com.packing.backend.api.user;

import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.Username;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateUserProfileRequest(
        @NotBlank @Size(min = Username.MIN_LENGTH, max = Username.MAX_LENGTH) String username,
        @Size(max = User.MAX_DISPLAY_NAME_LENGTH) String displayName) {
}
