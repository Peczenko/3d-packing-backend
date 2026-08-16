package com.packing.backend.api.packing;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.api.shared.security.CurrentUser;
import com.packing.backend.core.packing.PackingJobApplicationService;
import com.packing.backend.core.packing.PackingJobApplicationService.CreatePackingJobCommand;
import com.packing.backend.core.packing.PackingJobApplicationService.ListPackingJobsQuery;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobQuery;
import com.packing.backend.core.shared.PageRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
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
public class PackingJobController {

    private final PackingJobApplicationService jobs;

    @PostMapping
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
    public PackingJobResponse get(@CurrentUser AuthenticatedUser caller,
                                  @PathVariable UUID projectId,
                                  @PathVariable UUID jobId) {
        return PackingJobResponse.from(jobs.get(new PackingJobQuery(caller.firebaseUid(), projectId, jobId)));
    }
}
