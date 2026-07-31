package com.packing.backend.api.project;

import com.packing.backend.domain.project.ProjectPermission;
import jakarta.validation.constraints.NotNull;

public record ChangeMemberPermissionRequest(
        @NotNull ProjectPermission permission) {
}
