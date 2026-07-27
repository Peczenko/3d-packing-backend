package com.packing.backend.api.project;

import com.packing.backend.domain.project.ProjectPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param identifier the member's email address or username; the two are tried in that order,
 *                   and a miss reports the same error either way
 */
public record AddProjectMemberRequest(
        @NotBlank String identifier,
        @NotNull ProjectPermission permission) {
}
