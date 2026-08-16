package com.packing.backend.api.project;

import com.packing.backend.domain.project.ProjectPermission;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddProjectMemberRequest(
        @NotBlank @Schema(description = "Email or username of the user to add") String identifier,
        @NotNull ProjectPermission permission) {
}
