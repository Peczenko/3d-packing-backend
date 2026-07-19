package com.packing.backend.api.user;

import com.packing.backend.domain.user.UserRole;
import jakarta.validation.constraints.NotNull;

public record AssignUserRoleRequest(@NotNull UserRole role) {
}
