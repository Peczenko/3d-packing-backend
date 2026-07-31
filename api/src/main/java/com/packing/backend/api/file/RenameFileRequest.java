package com.packing.backend.api.file;

import com.packing.backend.domain.file.FileName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameFileRequest(
        @NotBlank @Size(max = FileName.MAX_LENGTH) String name) {
}
