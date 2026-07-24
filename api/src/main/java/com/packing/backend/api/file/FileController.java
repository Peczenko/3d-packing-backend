package com.packing.backend.api.file;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.api.shared.security.CurrentUser;
import com.packing.backend.core.file.port.in.DeleteFileUseCase;
import com.packing.backend.core.file.port.in.DownloadFileUseCase;
import com.packing.backend.core.file.port.in.ListFilesUseCase;
import com.packing.backend.core.file.port.in.UploadFileUseCase;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final UploadFileUseCase uploadFile;
    private final ListFilesUseCase listFiles;
    private final DownloadFileUseCase downloadFile;
    private final DeleteFileUseCase deleteFile;

    public FileController(UploadFileUseCase uploadFile,
                          ListFilesUseCase listFiles,
                          DownloadFileUseCase downloadFile,
                          DeleteFileUseCase deleteFile) {
        this.uploadFile = uploadFile;
        this.listFiles = listFiles;
        this.downloadFile = downloadFile;
        this.deleteFile = deleteFile;
    }

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
        return FileResponse.from(uploadFile.upload(new UploadFileUseCase.UploadFileCommand(
                caller.firebaseUid(),
                file.getOriginalFilename(),
                file.getSize(),
                file::getInputStream)));
    }

    @GetMapping
    public FilePageResponse list(
            @CurrentUser AuthenticatedUser caller,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return FilePageResponse.from(listFiles.listFiles(
                new ListFilesUseCase.ListFilesCommand(caller.firebaseUid(), page, size)));
    }

    /**
     * The {@code Location} header carries a bearer credential that outlives the response,
     * so the response must not be cached by a browser, proxy or CDN.
     */
    @GetMapping("/{fileId}/content")
    public ResponseEntity<Void> download(@CurrentUser AuthenticatedUser caller,
                                         @PathVariable UUID fileId) {
        DownloadFileUseCase.FileDownload download = downloadFile.prepareDownload(
                new DownloadFileUseCase.PrepareDownloadCommand(caller.firebaseUid(), fileId));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(download.url())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .build();
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> delete(@CurrentUser AuthenticatedUser caller,
                                       @PathVariable UUID fileId) {
        deleteFile.deleteFile(
                new DeleteFileUseCase.DeleteFileCommand(caller.firebaseUid(), fileId));
        return ResponseEntity.noContent().build();
    }
}
