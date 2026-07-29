package com.packing.backend.api.file;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.api.shared.security.CurrentUser;
import com.packing.backend.core.file.FileApplicationService;
import com.packing.backend.core.file.FileApplicationService.DeleteFileCommand;
import com.packing.backend.core.file.FileApplicationService.ListFilesCommand;
import com.packing.backend.core.file.FileApplicationService.PrepareDownloadCommand;
import com.packing.backend.core.file.FileApplicationService.RenameFileCommand;
import com.packing.backend.core.file.FileApplicationService.UploadFileCommand;
import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Files are reached only through their project. There is no flat {@code /api/v1/files}: one
 * route to a file means one authorisation rule, decided by the caller's permission on the
 * project rather than by who uploaded it.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/files")
@RequiredArgsConstructor
public class ProjectFileController {

    private final FileApplicationService files;

    /**
     * {@code MultipartFile::getInputStream} is handed over as the content source because it
     * returns a fresh stream on each call — the use case reads twice.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileResponse upload(@CurrentUser AuthenticatedUser caller,
                               @PathVariable UUID projectId,
                               @RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new DomainRuleViolationException("Uploaded file is empty");
        }
        return FileResponse.from(files.upload(new UploadFileCommand(
                caller.firebaseUid(),
                projectId,
                file.getOriginalFilename(),
                file.getSize(),
                file::getInputStream)));
    }

    @GetMapping
    public FilePageResponse list(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageRequest.MAX_SIZE) int size) {
        return FilePageResponse.from(files.listFiles(
                new ListFilesCommand(caller.firebaseUid(), projectId, new PageRequest(page, size))));
    }

    /**
     * The {@code Location} header carries a bearer credential that outlives the response,
     * so the response must not be cached by a browser, proxy or CDN.
     */
    @GetMapping("/{fileId}/content")
    public ResponseEntity<Void> download(@CurrentUser AuthenticatedUser caller,
                                         @PathVariable UUID projectId,
                                         @PathVariable UUID fileId) {
        BinaryStorage.TemporaryUrl download = files.prepareDownload(
                new PrepareDownloadCommand(caller.firebaseUid(), projectId, fileId));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(download.url())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .build();
    }

    /** Renames only. Content is immutable, so there is no endpoint to replace the bytes. */
    @PatchMapping("/{fileId}")
    public FileResponse rename(@CurrentUser AuthenticatedUser caller,
                               @PathVariable UUID projectId,
                               @PathVariable UUID fileId,
                               @Valid @RequestBody RenameFileRequest request) {
        return FileResponse.from(files.renameFile(new RenameFileCommand(
                caller.firebaseUid(), projectId, fileId, request.name())));
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> delete(@CurrentUser AuthenticatedUser caller,
                                       @PathVariable UUID projectId,
                                       @PathVariable UUID fileId) {
        files.deleteFile(new DeleteFileCommand(caller.firebaseUid(), projectId, fileId));
        return ResponseEntity.noContent().build();
    }
}
