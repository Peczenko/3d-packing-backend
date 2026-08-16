package com.packing.backend.api.packing;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.api.shared.security.CurrentUser;
import com.packing.backend.core.packing.PackingJobApplicationService;
import com.packing.backend.core.packing.PackingJobApplicationService.CreatePackingJobCommand;
import com.packing.backend.core.packing.PackingJobApplicationService.ListPackingJobsQuery;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobQuery;
import com.packing.backend.core.shared.PageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/packing-jobs")
@RequiredArgsConstructor
@Tag(name = "Packing Jobs", description = "Packing runs on a project and their results")
public class PackingJobController {

    private final PackingJobApplicationService jobs;

    @PostMapping
    @Operation(operationId = "createPackingJob", summary = "Queue a packing job")
    @ApiResponse(responseCode = "202",
                 description = "Job queued",
                 headers = @Header(name = HttpHeaders.LOCATION,
                                   description = "URL of the queued job",
                                   schema = @Schema(type = "string", format = "uri")))
    public ResponseEntity<PackingJobResponse> create(@CurrentUser AuthenticatedUser caller,
                                                     @PathVariable UUID projectId,
                                                     @Valid @RequestBody CreatePackingJobRequest request) {
        PackingJobResponse response = PackingJobResponse.from(jobs.create(new CreatePackingJobCommand(
                                                                                                      caller.firebaseUid(),
                                                                                                      projectId,
                                                                                                      request.spec()
                                                                                                             .toString(),
                                                                                                      request.effectiveMaxRuntimeSeconds())));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                                                  .path("/{jobId}")
                                                  .buildAndExpand(response.id())
                                                  .toUri();
        return ResponseEntity.accepted()
                             .location(location)
                             .body(response);
    }

    @GetMapping
    @Operation(operationId = "listPackingJobs", summary = "List the packing jobs of a project")
    @ApiResponse(responseCode = "200", description = "Page of packing jobs")
    public PackingJobPageResponse list(@CurrentUser AuthenticatedUser caller,
                                       @PathVariable UUID projectId,
                                       @RequestParam(defaultValue = "0") @Min(0) int page,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(PageRequest.MAX_SIZE) int size) {
        return PackingJobPageResponse.from(jobs.list(new ListPackingJobsQuery(
                                                                              caller.firebaseUid(),
                                                                              projectId,
                                                                              new PageRequest(page, size))));
    }

    @GetMapping("/{jobId}")
    @Operation(operationId = "getPackingJob", summary = "Get a packing job")
    @ApiResponse(responseCode = "200", description = "The packing job")
    public PackingJobResponse get(@CurrentUser AuthenticatedUser caller,
                                  @PathVariable UUID projectId,
                                  @PathVariable UUID jobId) {
        return PackingJobResponse.from(jobs.get(new PackingJobQuery(caller.firebaseUid(), projectId, jobId)));
    }
}
