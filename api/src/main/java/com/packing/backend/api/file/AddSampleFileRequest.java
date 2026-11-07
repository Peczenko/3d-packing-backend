package com.packing.backend.api.file;

import jakarta.validation.constraints.NotBlank;

public record AddSampleFileRequest(@NotBlank String sampleId) {
}
