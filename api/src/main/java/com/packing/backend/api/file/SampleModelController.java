package com.packing.backend.api.file;

import com.packing.backend.core.file.FileApplicationService;
import com.packing.backend.core.file.SampleModel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sample-models")
@RequiredArgsConstructor
@Tag(name = "Sample Models", description = "Ready-made 3D models that can be added to a project")
public class SampleModelController {

    private final FileApplicationService files;

    @GetMapping
    @Operation(operationId = "listSampleModels", summary = "List the sample models a project can start from")
    @ApiResponse(responseCode = "200", description = "Every sample model")
    public List<SampleModelResponse> list() {
        return files.listSamples()
                    .stream()
                    .map(SampleModelResponse::from)
                    .toList();
    }

    @GetMapping("/{sampleId}/content")
    @Operation(operationId = "downloadSampleModel", summary = "The bytes of a sample model, for previewing it")
    @ApiResponse(responseCode = "200", description = "The sample model")
    @ApiResponse(responseCode = "304", description = "The sample matches If-None-Match")
    public ResponseEntity<Resource> content(@PathVariable String sampleId) {
        SampleModel sample = files.sample(sampleId);
        return ResponseEntity.ok()
                             .eTag(sample.checksum()
                                         .value())
                             .cacheControl(CacheControl.noCache())
                             .contentType(MediaType.parseMediaType(sample.format()
                                                                         .contentType()))
                             .contentLength(sample.sizeBytes())
                             .body(new InputStreamResource(sample.content()::open, "sample model " + sample.id()));
    }
}
