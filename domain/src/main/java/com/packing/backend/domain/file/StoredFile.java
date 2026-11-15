package com.packing.backend.domain.file;

import com.packing.backend.domain.file.event.FileDeleted;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.shared.AggregateRoot;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.UserId;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public final class StoredFile extends AggregateRoot {

    public static final long MAX_SIZE_BYTES = 100L * 1024 * 1024;

    public static final long INITIAL_VERSION = 0L;

    @EqualsAndHashCode.Include
    private final FileId id;

    private final UserId       ownerId;
    private final ProjectId    projectId;
    private final StorageKey   storageKey;
    private final ModelFormat  format;
    private final long         sizeBytes;
    private final Checksum     checksum;
    private final ConvertedObj convertedObj;
    private final Instant      createdAt;

    private FileName   name;
    private FileStatus status;
    private long       version;
    private Instant    updatedAt;
    private Instant    deletedAt;

    private StoredFile(FileId id,
                       UserId ownerId,
                       ProjectId projectId,
                       FileName name,
                       StorageKey storageKey,
                       ModelFormat format,
                       long sizeBytes,
                       Checksum checksum,
                       ConvertedObj convertedObj,
                       FileStatus status,
                       long version,
                       Instant createdAt,
                       Instant updatedAt,
                       Instant deletedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.name = Objects.requireNonNull(name, "name");
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey");
        this.format = Objects.requireNonNull(format, "format");
        this.sizeBytes = sizeBytes;
        this.checksum = Objects.requireNonNull(checksum, "checksum");
        this.convertedObj = convertedObj;
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.deletedAt = deletedAt;
    }

    public static StoredFile upload(FileId id,
                                    UserId ownerId,
                                    ProjectId projectId,
                                    FileName name,
                                    long sizeBytes,
                                    Checksum checksum,
                                    Instant now) {
        return upload(id, ownerId, projectId, name, sizeBytes, checksum, null, now);
    }

    public static StoredFile upload(FileId id,
                                    UserId ownerId,
                                    ProjectId projectId,
                                    FileName name,
                                    long sizeBytes,
                                    Checksum checksum,
                                    ConvertedObj convertedObj,
                                    Instant now) {
        ModelFormat format = name.format();
        format.requireUploadable();
        requireValidSize(sizeBytes);
        requireConversionMatches(id, name, format, convertedObj);
        return new StoredFile(
                              id,
                              ownerId,
                              projectId,
                              name,
                              StorageKey.forFile(id),
                              format,
                              sizeBytes,
                              checksum,
                              convertedObj,
                              FileStatus.AVAILABLE,
                              INITIAL_VERSION,
                              now,
                              now,
                              null);
    }

    public static StoredFile rehydrate(FileId id,
                                       UserId ownerId,
                                       ProjectId projectId,
                                       FileName name,
                                       StorageKey storageKey,
                                       ModelFormat format,
                                       long sizeBytes,
                                       Checksum checksum,
                                       ConvertedObj convertedObj,
                                       FileStatus status,
                                       long version,
                                       Instant createdAt,
                                       Instant updatedAt,
                                       Instant deletedAt) {
        return new StoredFile(id,
                              ownerId,
                              projectId,
                              name,
                              storageKey,
                              format,
                              sizeBytes,
                              checksum,
                              convertedObj,
                              status,
                              version,
                              createdAt,
                              updatedAt,
                              deletedAt);
    }

    public void rename(FileName newName, Instant now) {
        Objects.requireNonNull(newName, "name");
        if (isDeleted()) {
            throw new DomainRuleViolationException("Cannot rename a deleted file: " + id);
        }
        if (this.name.equals(newName)) {
            return;
        }
        if (newName.format() != format) {
            throw new DomainRuleViolationException(
                                                   "Cannot rename a " + format + " file to " + newName
                                                           + ": the extension must keep the same format");
        }
        this.name = newName;
        this.updatedAt = now;
    }

    public void delete(Instant now) {
        if (isDeleted()) {
            return;
        }
        this.status = FileStatus.DELETED;
        this.deletedAt = now;
        this.updatedAt = now;
        recordEvent(new FileDeleted(id, storageKeys(), ownerId, now));
    }

    public Optional<ModelContent> packingContent() {
        if (convertedObj != null) {
            return Optional.of(new ModelContent(convertedObj.storageKey(), ModelFormat.OBJ, convertedObj.checksum()));
        }
        if (format == ModelFormat.OBJ) {
            return Optional.of(new ModelContent(storageKey, format, checksum));
        }
        return Optional.empty();
    }

    public List<StorageKey> storageKeys() {
        return convertedObj == null ? List.of(storageKey) : List.of(storageKey, convertedObj.storageKey());
    }

    public boolean isDeleted() {
        return status == FileStatus.DELETED;
    }

    public boolean belongsTo(ProjectId candidate) {
        return projectId.equals(candidate);
    }

    public boolean isAvailableIn(ProjectId candidate) {
        return belongsTo(candidate) && !isDeleted();
    }

    public String contentType() {
        return format.contentType();
    }

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

    private static void requireConversionMatches(FileId id, FileName name, ModelFormat format, ConvertedObj convertedObj) {
        if (format == ModelFormat.OBJ) {
            if (convertedObj != null) {
                throw new DomainRuleViolationException(name + " is already OBJ and must not carry a converted copy");
            }
            return;
        }
        if (convertedObj == null) {
            throw new DomainRuleViolationException(name + " must be converted to OBJ before it can be stored");
        }
        if (!convertedObj.storageKey()
                         .equals(StorageKey.forConvertedObj(id))) {
            throw new DomainRuleViolationException("The converted copy of " + name + " must be stored at " + StorageKey.forConvertedObj(id));
        }
        if (convertedObj.sizeBytes() <= 0) {
            throw new DomainRuleViolationException(name + " converted to an empty OBJ");
        }
        if (convertedObj.sizeBytes() > ConvertedObj.MAX_SIZE_BYTES) {
            throw new DomainRuleViolationException(
                                                   name + " converts to an OBJ of " + convertedObj.sizeBytes() + " bytes; at most "
                                                           + ConvertedObj.MAX_SIZE_BYTES + " are allowed");
        }
    }

    @Override
    public String toString() {
        return "StoredFile[id=" + id + ", name=" + name + ", size=" + sizeBytes
                + ", status=" + status + "]";
    }
}
