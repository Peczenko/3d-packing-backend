package com.packing.backend.api.packing;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.api.shared.security.CurrentUser;
import com.packing.backend.core.packing.PackingJobApplicationService;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobPreviewQuery;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobResultBinQuery;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobResultQuery;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.domain.packing.PreviewQuality;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/packing-jobs")
@RequiredArgsConstructor
@Tag(name = "Packing Jobs", description = "Packing runs on a project and their results")
public class PackingJobResultController {

    private final PackingJobApplicationService jobs;

    @GetMapping("/{jobId}/result")
    @Operation(operationId = "downloadPackingJobResult",
               summary = "Redirect to a short-lived download URL for a job result")
    @ApiResponse(responseCode = "302",
                 description = "Redirect to the result file",
                 headers = @Header(name = HttpHeaders.LOCATION,
                                   description = "Short-lived storage URL",
                                   schema = @Schema(type = "string", format = "uri")))
    public ResponseEntity<Void> download(@CurrentUser AuthenticatedUser caller,
                                         @PathVariable UUID projectId,
                                         @PathVariable UUID jobId) {
        return redirect(jobs.prepareResultDownload(new PackingJobResultQuery(caller.firebaseUid(), projectId, jobId)));
    }

    @GetMapping("/{jobId}/result/bins/{number}")
    @Operation(operationId = "downloadPackingJobResultBin",
               summary = "Redirect to a short-lived download URL for one filled container of a job result")
    @ApiResponse(responseCode = "302",
                 description = "Redirect to the container's OBJ, in its own coordinates",
                 headers = @Header(name = HttpHeaders.LOCATION,
                                   description = "Short-lived storage URL",
                                   schema = @Schema(type = "string", format = "uri")))
    public ResponseEntity<Void> downloadBin(@CurrentUser AuthenticatedUser caller,
                                            @PathVariable UUID projectId,
                                            @PathVariable UUID jobId,
                                            @Parameter(description = "One-based container number, as listed in resultBins")
                                            @PathVariable int number) {
        return redirect(jobs.prepareResultBinDownload(new PackingJobResultBinQuery(caller.firebaseUid(), projectId, jobId, number)));
    }

    @GetMapping("/{jobId}/preview/{quality}")
    @Operation(operationId = "downloadPackingJobPreview",
               summary = "Redirect to a short-lived URL for a lighter glTF preview of a job result")
    @ApiResponse(responseCode = "302",
                 description = "Redirect to the preview model",
                 headers = @Header(name = HttpHeaders.LOCATION,
                                   description = "Short-lived storage URL",
                                   schema = @Schema(type = "string", format = "uri")))
    public ResponseEntity<Void> preview(@CurrentUser AuthenticatedUser caller,
                                        @PathVariable UUID projectId,
                                        @PathVariable UUID jobId,
                                        @PathVariable PreviewQuality quality,
                                        @Parameter(description = "Ask the browser to save the file instead of handing it to a viewer")
                                        @RequestParam(defaultValue = "false") boolean download) {
        return redirect(jobs.preparePreviewDownload(new PackingJobPreviewQuery(caller.firebaseUid(),
                                                                               projectId,
                                                                               jobId,
                                                                               quality,
                                                                               download)));
    }

    private static ResponseEntity<Void> redirect(PackingJobArtifactStore.TemporaryUrl target) {
        return ResponseEntity.status(HttpStatus.FOUND)
                             .location(target.url())
                             .cacheControl(CacheControl.noStore())
                             .header(HttpHeaders.PRAGMA, "no-cache")
                             .build();
    }
}
