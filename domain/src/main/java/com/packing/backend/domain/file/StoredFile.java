package com.packing.backend.domain.file;

import com.packing.backend.domain.file.event.FileDeleted;
import com.packing.backend.domain.shared.AggregateRoot;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.UserId;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link #storageKey()} is derived from the id before either system is written, so a
 * retried upload overwrites its own blob instead of leaving an orphan. {@code projectId}
 * is always null: the project aggregate does not exist yet.
 */
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public final class StoredFile extends AggregateRoot {

    /**
     * Also mirrored in {@code spring.servlet.multipart.max-file-size} — a properties file
     * can't reference a Java constant, so keep the two in sync by hand.
     */
    public static final long MAX_SIZE_BYTES = 100L * 1024 * 1024;

    public static final long INITIAL_VERSION = 0L;

    @EqualsAndHashCode.Include
    private final FileId id;

    private final UserId ownerId;
    private final FileName name;
    private final StorageKey storageKey;
    private final ModelFormat format;
    private final long sizeBytes;
    private final Checksum checksum;
    private final Instant createdAt;

    private UUID projectId;
    private FileStatus status;
    private long version;
    private Instant updatedAt;
    private Instant deletedAt;

    private StoredFile(FileId id,
                       UserId ownerId,
                       UUID projectId,
                       FileName name,
                       StorageKey storageKey,
                       ModelFormat format,
                       long sizeBytes,
                       Checksum checksum,
                       FileStatus status,
                       long version,
                       Instant createdAt,
                       Instant updatedAt,
                       Instant deletedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.projectId = projectId;
        this.name = Objects.requireNonNull(name, "name");
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey");
        this.format = Objects.requireNonNull(format, "format");
        this.sizeBytes = sizeBytes;
        this.checksum = Objects.requireNonNull(checksum, "checksum");
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.deletedAt = deletedAt;
    }

    /**
     * @param sizeBytes the number of bytes actually received, never a client-declared value
     */
    public static StoredFile upload(FileId id,
                                    UserId ownerId,
                                    FileName name,
                                    long sizeBytes,
                                    Checksum checksum,
                                    Instant now) {
        requireValidSize(sizeBytes);
        return new StoredFile(
                id,
                ownerId,
                null,
                name,
                StorageKey.forFile(id),
                name.format(),
                sizeBytes,
                checksum,
                FileStatus.AVAILABLE,
                INITIAL_VERSION,
                now,
                now,
                null);
    }

    /** Only the persistence adapter should call this. */
    public static StoredFile rehydrate(FileId id,
                                       UserId ownerId,
                                       UUID projectId,
                                       FileName name,
                                       StorageKey storageKey,
                                       ModelFormat format,
                                       long sizeBytes,
                                       Checksum checksum,
                                       FileStatus status,
                                       long version,
                                       Instant createdAt,
                                       Instant updatedAt,
                                       Instant deletedAt) {
        return new StoredFile(id, ownerId, projectId, name, storageKey, format, sizeBytes,
                checksum, status, version, createdAt, updatedAt, deletedAt);
    }

    /** Idempotent: deleting twice is a no-op that records only one event. */
    public void delete(Instant now) {
        if (isDeleted()) {
            return;
        }
        this.status = FileStatus.DELETED;
        this.deletedAt = now;
        this.updatedAt = now;
        recordEvent(new FileDeleted(id, storageKey, ownerId, now));
    }

    public boolean isDeleted() {
        return status == FileStatus.DELETED;
    }

    public boolean isOwnedBy(UserId candidate) {
        return ownerId.equals(candidate);
    }

    public String contentType() {
        return format.contentType();
    }

    /**
     * Bumps the version after a successful save, so a second save in the same unit of work
     * is not rejected as stale.
     */
    public void markPersisted() {
        this.version++;
    }

    private static void requireValidSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new DomainRuleViolationException("File must not be empty");
        }
        if (sizeBytes > MAX_SIZE_BYTES) {
            throw new DomainRuleViolationException(
                    "File must be at most " + MAX_SIZE_BYTES + " bytes, got " + sizeBytes);
        }
    }

    @Override
    public String toString() {
        return "StoredFile[id=" + id + ", name=" + name + ", size=" + sizeBytes
                + ", status=" + status + "]";
    }
}
