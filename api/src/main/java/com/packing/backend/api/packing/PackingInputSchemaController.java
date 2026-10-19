package com.packing.backend.api.packing;

import com.packing.backend.core.packing.PackingJobApplicationService;
import com.packing.backend.core.packing.port.out.PackingInputSchema.SchemaDocument;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/packing")
@RequiredArgsConstructor
@Tag(name = "Packing Jobs", description = "Packing runs on a project and their results")
public class PackingInputSchemaController {

    private static final MediaType SCHEMA_JSON = MediaType.parseMediaType("application/schema+json");

    private final PackingJobApplicationService jobs;

    @GetMapping("/input-schema")
    @Operation(operationId = "getPackingInputSchema",
               summary = "The JSON Schema every packing job input is validated against")
    @ApiResponse(responseCode = "200", description = "The current input schema")
    @ApiResponse(responseCode = "304", description = "The schema matches If-None-Match")
    public ResponseEntity<String> inputSchema() {
        SchemaDocument document = jobs.inputSchema();
        return ResponseEntity.ok()
                             .eTag(document.etag())
                             .cacheControl(CacheControl.noCache())
                             .contentType(SCHEMA_JSON)
                             .body(document.json());
    }
}
