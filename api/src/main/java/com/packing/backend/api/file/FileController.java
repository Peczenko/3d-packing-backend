package com.packing.backend.api.file;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.api.shared.security.CurrentUser;
import com.packing.backend.core.file.FileApplicationService;
import com.packing.backend.core.file.FileApplicationService.DeleteFileCommand;
import com.packing.backend.core.file.FileApplicationService.ListFilesCommand;
import com.packing.backend.core.file.FileApplicationService.PrepareDownloadCommand;
import com.packing.backend.core.file.FileApplicationService.UploadFileCommand;
import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileApplicationService files;

    /**
     * {@code MultipartFile::getInputStream} is handed over as the content source because it
     * returns a fresh stream on each call — the use case reads twice.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileResponse upload(@CurrentUser AuthenticatedUser caller,
                               @RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new DomainRuleViolationException("Uploaded file is empty");
        }
        return FileResponse.from(files.upload(new UploadFileCommand(
                caller.firebaseUid(),
                file.getOriginalFilename(),
                file.getSize(),
                file::getInputStream)));
    }

    @GetMapping
    public FilePageResponse list(
            @CurrentUser AuthenticatedUser caller,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ListFilesCommand.MAX_SIZE) int size) {
        return FilePageResponse.from(files.listFiles(
                new ListFilesCommand(caller.firebaseUid(), page, size)));
    }

    /**
     * The {@code Location} header carries a bearer credential that outlives the response,
     * so the response must not be cached by a browser, proxy or CDN.
     */
    @GetMapping("/{fileId}/content")
    public ResponseEntity<Void> download(@CurrentUser AuthenticatedUser caller,
                                         @PathVariable UUID fileId) {
        BinaryStorage.TemporaryUrl download = files.prepareDownload(
                new PrepareDownloadCommand(caller.firebaseUid(), fileId));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(download.url())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .build();
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> delete(@CurrentUser AuthenticatedUser caller,
                                       @PathVariable UUID fileId) {
        files.deleteFile(new DeleteFileCommand(caller.firebaseUid(), fileId));
        return ResponseEntity.noContent().build();
    }
}
