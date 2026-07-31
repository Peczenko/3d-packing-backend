package com.packing.backend.api.project;

import com.packing.backend.domain.project.ProjectName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameProjectRequest(
        @NotBlank @Size(max = ProjectName.MAX_LENGTH) String name) {
}
