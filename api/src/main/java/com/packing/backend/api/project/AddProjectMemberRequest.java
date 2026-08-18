package com.packing.backend.api.project;

import com.packing.backend.domain.project.ProjectPermission;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddProjectMemberRequest(
        @NotNull @Schema(description = "ID returned by user search") UUID userId,
        @NotNull ProjectPermission permission) {
}
