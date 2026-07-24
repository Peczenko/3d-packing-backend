package com.packing.backend.core.file;

import com.packing.backend.core.file.port.in.DeleteFileUseCase;
import com.packing.backend.core.file.port.in.DownloadFileUseCase;
import com.packing.backend.core.file.port.in.ListFilesUseCase;
import com.packing.backend.core.file.port.in.UploadFileUseCase;
import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.file.port.out.FileOwnerLookup;
import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.core.shared.ExternalServiceException;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.file.StoredFileNotFoundException;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserNotFoundException;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class FileApplicationService implements
        UploadFileUseCase,
        DownloadFileUseCase,
        ListFilesUseCase,
        DeleteFileUseCase {

    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final int DIGEST_BUFFER_BYTES = 8192;

    private final FileRepository files;
    private final BinaryStorage storage;
    private final FileOwnerLookup ownerLookup;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock;

    public FileApplicationService(FileRepository files,
                                  BinaryStorage storage,
                                  FileOwnerLookup ownerLookup,
                                  DomainEventPublisher eventPublisher,
                                  Clock clock) {
        this.files = Objects.requireNonNull(files, "files");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.ownerLookup = Objects.requireNonNull(ownerLookup, "ownerLookup");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

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
    @Override
    public FileView upload(UploadFileCommand command) {
        UserId owner = requireActiveOwner(command.firebaseUid());
        FileName name = new FileName(command.originalFilename());
        FileId id = FileId.generate();

        // The client's declared size is never trusted: it is caller-controlled, and the
        // domain limit must hold against what was really received.
        Content content = digestAndCount(command.content());

        StoredFile file = StoredFile.upload(id, owner, name, content.sizeBytes(),
                content.checksum(), clock.instant());

        try (InputStream stream = command.content().open()) {
            storage.write(file.storageKey(), stream, file.sizeBytes(), file.contentType());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the upload for file " + id, e);
        }

        files.save(file);
        return FileView.from(file);
    }

    @Override
    @Transactional(readOnly = true)
    public FileDownload prepareDownload(PrepareDownloadCommand command) {
        UserId owner = requireActiveOwner(command.firebaseUid());
        StoredFile file = requireReachable(command.fileId(), owner);

        BinaryStorage.TemporaryUrl url = storage.temporaryReadUrl(
                file.storageKey(), file.name().value(), file.contentType());
        return new FileDownload(url.url(), url.expiresAt());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FileView> listFiles(ListFilesCommand command) {
        UserId owner = requireActiveOwner(command.firebaseUid());

        int offset = command.page() * command.size();
        List<FileView> content = files.findAvailableByOwner(owner, offset, command.size())
                .stream()
                .map(FileView::from)
                .toList();

        return new Page<>(content, command.page(), command.size(),
                files.countAvailableByOwner(owner));
    }

    /**
     * The blob is not touched here: PostgreSQL and the object store cannot share a
     * transaction, so deletion is handled after commit in the infrastructure layer.
     * {@code @Transactional} is required even for this single write, because an
     * after-commit listener only fires if there is a transaction to commit.
     */
    @Override
    @Transactional
    public void deleteFile(DeleteFileCommand command) {
        UserId owner = requireActiveOwner(command.firebaseUid());
        StoredFile file = requireReachable(command.fileId(), owner);

        file.delete(clock.instant());
        StoredFile saved = files.save(file);
        eventPublisher.publishAll(saved.pullDomainEvents());
    }

    private UserId requireActiveOwner(String firebaseUid) {
        FirebaseUid uid = new FirebaseUid(firebaseUid);
        return ownerLookup.findActiveOwner(uid)
                .orElseThrow(() -> UserNotFoundException.byFirebaseUid(uid));
    }

    /**
     * Absent, deleted and someone else's all collapse into the same 404. Distinguishing
     * them would confirm that an id exists, which turns the endpoint into an enumeration
     * oracle.
     */
    private StoredFile requireReachable(UUID fileId, UserId owner) {
        FileId id = new FileId(fileId);
        return files.findById(id)
                .filter(file -> file.isOwnedBy(owner))
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
}
