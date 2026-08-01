package com.packing.backend.api.packing;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.api.shared.security.CurrentUser;
import com.packing.backend.core.packing.PackingJobApplicationService;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobResultQuery;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/packing-jobs")
@RequiredArgsConstructor
public class PackingJobResultController {

    private final PackingJobApplicationService jobs;

    @GetMapping("/{jobId}/result")
    public ResponseEntity<Void> download(@CurrentUser AuthenticatedUser caller,
                                         @PathVariable UUID projectId,
                                         @PathVariable UUID jobId) {
        PackingJobArtifactStore.TemporaryUrl download = jobs.prepareResultDownload(
                new PackingJobResultQuery(caller.firebaseUid(), projectId, jobId));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(download.url())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .build();
    }
}
