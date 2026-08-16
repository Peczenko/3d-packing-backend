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
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/files")
@RequiredArgsConstructor
public class ProjectFileController {

    private final FileApplicationService files;

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

    @PatchMapping("/{fileId}")
    public FileResponse rename(@CurrentUser AuthenticatedUser caller,
                               @PathVariable UUID projectId,
                               @PathVariable UUID fileId,
                               @Valid @RequestBody RenameFileRequest request) {
        return FileResponse.from(files.renameFile(new RenameFileCommand(
                                                                        caller.firebaseUid(),
                                                                        projectId,
                                                                        fileId,
                                                                        request.name())));
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> delete(@CurrentUser AuthenticatedUser caller,
                                       @PathVariable UUID projectId,
                                       @PathVariable UUID fileId) {
        files.deleteFile(new DeleteFileCommand(caller.firebaseUid(), projectId, fileId));
        return ResponseEntity.noContent()
                             .build();
    }
}
