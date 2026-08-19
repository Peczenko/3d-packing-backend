package com.packing.backend.core.file;

import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.file.port.out.FileFinder;
import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.project.port.out.ProjectAccessLookup;
import com.packing.backend.core.project.port.out.ProjectAccessLookup.ProjectAccess;
import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.file.StoredFileNotFoundException;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectNotFoundException;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.FirebaseUid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileApplicationService {

    private static final String DIGEST_ALGORITHM    = "SHA-256";
    private static final int    DIGEST_BUFFER_BYTES = 8192;

    private final FileRepository       files;
    private final FileFinder           fileFinder;
    private final BinaryStorage        storage;
    private final ProjectAccessLookup  projectAccess;
    private final DomainEventPublisher eventPublisher;
    private final Clock                clock;

    public FileView upload(UploadFileCommand command) {
        ProjectAccess access = requireAccess(command.firebaseUid(),
                                             command.projectId(),
                                             ProjectPermission.WRITE).requireWritable();
        FileName name = new FileName(command.originalFilename());
        FileId id = FileId.generate();

        Content content = digestAndCount(command.content());

        StoredFile file = StoredFile.upload(id,
                                            access.userId(),
                                            access.projectId(),
                                            name,
                                            content.sizeBytes(),
                                            content.checksum(),
                                            clock.instant());

        try (InputStream stream = command.content()
                                         .open()) {
            storage.write(file.storageKey(), stream, file.sizeBytes(), file.contentType());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the upload for file " + id, e);
        }

        files.save(file);
        return FileView.from(file);
    }

    @Transactional(readOnly = true)
    public BinaryStorage.TemporaryUrl prepareDownload(PrepareDownloadCommand command) {
        ProjectAccess access = requireAccess(command.firebaseUid(),
                                             command.projectId(),
                                             ProjectPermission.READ);
        StoredFile file = requireReachable(command.fileId(), access.projectId());

        return storage.temporaryReadUrl(
                                        file.storageKey(),
                                        file.name()
                                            .value(),
                                        file.contentType());
    }

    @Transactional(readOnly = true)
    public Page<FileView> listFiles(ListFilesCommand command) {
        ProjectAccess access = requireAccess(command.firebaseUid(),
                                             command.projectId(),
                                             ProjectPermission.READ);
        return fileFinder.listAvailableInProject(access.projectId(), command.criteria());
    }

    @Transactional
    public FileView renameFile(RenameFileCommand command) {
        ProjectAccess access = requireAccess(command.firebaseUid(),
                                             command.projectId(),
                                             ProjectPermission.WRITE).requireWritable();
        StoredFile file = requireReachable(command.fileId(), access.projectId());

        file.rename(new FileName(command.name()), clock.instant());
        return FileView.from(files.save(file));
    }

    @Transactional
    public void deleteFile(DeleteFileCommand command) {
        ProjectAccess access = requireAccess(command.firebaseUid(),
                                             command.projectId(),
                                             ProjectPermission.WRITE).requireWritable();
        StoredFile file = requireReachable(command.fileId(), access.projectId());

        file.delete(clock.instant());
        StoredFile saved = files.save(file);
        eventPublisher.publishAll(saved.pullDomainEvents());
    }

    private ProjectAccess requireAccess(String firebaseUid, UUID projectId,
                                        ProjectPermission required) {
        ProjectId id = new ProjectId(projectId);
        return projectAccess.findAccess(new FirebaseUid(firebaseUid), id)
                            .orElseThrow(() -> ProjectNotFoundException.byId(id))
                            .requireAtLeast(required);
    }

    private StoredFile requireReachable(UUID fileId, ProjectId projectId) {
        FileId id = new FileId(fileId);
        return files.findById(id)
                    .filter(file -> file.belongsTo(projectId))
                    .filter(file -> !file.isDeleted())
                    .orElseThrow(() -> StoredFileNotFoundException.byId(id));
    }

    private Content digestAndCount(ContentSource source) {
        MessageDigest digest = newDigest();
        long size = 0;

        try (InputStream stream = source.open()) {
            byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
                if (size > StoredFile.MAX_SIZE_BYTES) {
                    throw new DomainRuleViolationException(
                                                           "File must be at most " + StoredFile.MAX_SIZE_BYTES + " bytes");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the upload", e);
        }

        return new Content(size,
                           Checksum.ofHex(HexFormat.of()
                                                   .formatHex(digest.digest())));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new ExternalServiceException("jre", DIGEST_ALGORITHM + " is unavailable", e);
        }
    }

    private record Content(long sizeBytes, Checksum checksum) {
    }

    public record UploadFileCommand(String firebaseUid,
            UUID projectId,
            String originalFilename,
            long declaredSizeBytes,
            ContentSource content) {
    }

    public record PrepareDownloadCommand(String firebaseUid, UUID projectId, UUID fileId) {
    }

    public record RenameFileCommand(String firebaseUid, UUID projectId, UUID fileId, String name) {
    }

    public record ListFilesCommand(String firebaseUid, UUID projectId, FileListCriteria criteria) {
    }

    public record DeleteFileCommand(String firebaseUid, UUID projectId, UUID fileId) {
    }
}
