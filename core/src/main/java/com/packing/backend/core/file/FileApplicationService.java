package com.packing.backend.core.file;

import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.file.port.out.FileFinder;
import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.project.port.out.ProjectAccessLookup;
import com.packing.backend.core.project.port.out.ProjectAccessLookup.ProjectAccess;
import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
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

/**
 * Every operation authorises against the caller's permission on the owning project. A file's
 * {@code ownerId} records who uploaded it and grants nothing — a WRITE member may delete or
 * rename anything in the project, including files they did not upload.
 */
@Service
@RequiredArgsConstructor
public class FileApplicationService {

    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final int DIGEST_BUFFER_BYTES = 8192;

    private final FileRepository files;
    private final FileFinder fileFinder;
    private final BinaryStorage storage;
    private final ProjectAccessLookup projectAccess;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Not transactional: the blob write can move a hundred megabytes over the network, and
     * holding a pooled JDBC connection open for that long would make connection-pool
     * exhaustion a function of upload bandwidth.
     *
     * <p>Blob is written before the row, deliberately: if the insert then fails, the blob
     * is orphaned but invisible and costs only storage, whereas the reverse order would
     * leave a row that 404s on download. This is a known, accepted limitation (P1 in
     * {@code docs/repo-scalability-audit.md}); the {@code files/{uuid}} key layout keeps a
     * future reconciliation sweep to a set difference against {@code storage_key}.
     */
    public FileView upload(UploadFileCommand command) {
        ProjectAccess access = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.WRITE).requireWritable();
        FileName name = new FileName(command.originalFilename());
        FileId id = FileId.generate();

        // The client's declared size is never trusted: it is caller-controlled, and the
        // domain limit must hold against what was really received.
        Content content = digestAndCount(command.content());

        StoredFile file = StoredFile.upload(id, access.userId(), access.projectId(), name,
                content.sizeBytes(), content.checksum(), clock.instant());

        try (InputStream stream = command.content().open()) {
            storage.write(file.storageKey(), stream, file.sizeBytes(), file.contentType());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the upload for file " + id, e);
        }

        files.save(file);
        return FileView.from(file);
    }

    /** The returned URL embeds a credential. It must not be logged, cached or stored. */
    @Transactional(readOnly = true)
    public BinaryStorage.TemporaryUrl prepareDownload(PrepareDownloadCommand command) {
        ProjectAccess access = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.READ);
        StoredFile file = requireReachable(command.fileId(), access.projectId());

        return storage.temporaryReadUrl(
                file.storageKey(), file.name().value(), file.contentType());
    }

    @Transactional(readOnly = true)
    public Page<FileView> listFiles(ListFilesCommand command) {
        ProjectAccess access = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.READ);
        return fileFinder.listAvailableInProject(access.projectId(), command.page());
    }

    @Transactional
    public FileView renameFile(RenameFileCommand command) {
        ProjectAccess access = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.WRITE).requireWritable();
        StoredFile file = requireReachable(command.fileId(), access.projectId());

        file.rename(new FileName(command.name()), clock.instant());
        return FileView.from(files.save(file));
    }

    /**
     * The blob is not touched here: PostgreSQL and the object store cannot share a
     * transaction, so deletion is handled after commit in the infrastructure layer.
     * {@code @Transactional} is required even for this single write, because an
     * after-commit listener only fires if there is a transaction to commit.
     */
    @Transactional
    public void deleteFile(DeleteFileCommand command) {
        ProjectAccess access = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.WRITE).requireWritable();
        StoredFile file = requireReachable(command.fileId(), access.projectId());

        file.delete(clock.instant());
        StoredFile saved = files.save(file);
        eventPublisher.publishAll(saved.pullDomainEvents());
    }

    /**
     * An inactive caller, a deleted project and a project the caller is not a member of all
     * collapse into the same 404 — anything else would confirm that a project id exists.
     */
    private ProjectAccess requireAccess(String firebaseUid, UUID projectId,
                                        ProjectPermission required) {
        ProjectId id = new ProjectId(projectId);
        return projectAccess.findAccess(new FirebaseUid(firebaseUid), id)
                .orElseThrow(() -> ProjectNotFoundException.byId(id))
                .requireAtLeast(required);
    }

    /**
     * Absent, deleted and belonging to another project all collapse into the same 404.
     * Distinguishing them would confirm that an id exists, which turns the endpoint into an
     * enumeration oracle.
     */
    private StoredFile requireReachable(UUID fileId, ProjectId projectId) {
        FileId id = new FileId(fileId);
        return files.findById(id)
                .filter(file -> file.belongsTo(projectId))
                .filter(file -> !file.isDeleted())
                .orElseThrow(() -> StoredFileNotFoundException.byId(id));
    }

    /**
     * Size is re-checked here as well as in the aggregate, so an oversized stream is
     * rejected before any bytes reach the blob write.
     */
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

        return new Content(size, Checksum.ofHex(HexFormat.of().formatHex(digest.digest())));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JRE, so this is a broken runtime, not a
            // condition a caller can do anything about.
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

    public record ListFilesCommand(String firebaseUid, UUID projectId, PageRequest page) {
    }

    public record DeleteFileCommand(String firebaseUid, UUID projectId, UUID fileId) {
    }
}
