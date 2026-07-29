package com.packing.backend.infra.persistence.file;

import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.FileStatus;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.infra.persistence.jooq.tables.records.FilesRecord;
import com.packing.backend.infra.persistence.shared.AggregateTable;
import com.packing.backend.infra.persistence.shared.Timestamps;
import org.jooq.Field;

import java.util.Set;

import static com.packing.backend.infra.persistence.jooq.tables.Files.FILES;

final class FileRecordMapper {

    static final AggregateTable<FilesRecord> TABLE = new AggregateTable<>(
            "File", FILES.VERSION, Set.<Field<?>>of(
                    FILES.OWNER_USER_ID, FILES.PROJECT_ID, FILES.STORAGE_KEY, FILES.FORMAT,
                    FILES.CONTENT_TYPE, FILES.SIZE_BYTES, FILES.CHECKSUM_SHA256,
                    FILES.CREATED_AT));

    private FileRecordMapper() {
    }

    static StoredFile toDomain(FilesRecord record) {
        return StoredFile.rehydrate(
                new FileId(record.getId()),
                new UserId(record.getOwnerUserId()),
                new ProjectId(record.getProjectId()),
                new FileName(record.getOriginalFilename()),
                new StorageKey(record.getStorageKey()),
                ModelFormat.valueOf(record.getFormat()),
                record.getSizeBytes(),
                Checksum.ofHex(record.getChecksumSha256()),
                FileStatus.valueOf(record.getStatus()),
                record.getVersion(),
                Timestamps.toInstant(record.getCreatedAt()),
                Timestamps.toInstant(record.getUpdatedAt()),
                Timestamps.toInstant(record.getDeletedAt()));
    }

    static FilesRecord toRecord(StoredFile file) {
        FilesRecord record = new FilesRecord();
        record.setId(file.id().value());
        record.setOwnerUserId(file.ownerId().value());
        record.setProjectId(file.projectId().value());
        record.setOriginalFilename(file.name().value());
        record.setStorageKey(file.storageKey().value());
        record.setFormat(file.format().name());
        record.setContentType(file.contentType());
        record.setSizeBytes(file.sizeBytes());
        record.setChecksumSha256(file.checksum().value());
        record.setStatus(file.status().name());
        record.setVersion(file.version());
        record.setCreatedAt(Timestamps.toOffsetDateTime(file.createdAt()));
        record.setUpdatedAt(Timestamps.toOffsetDateTime(file.updatedAt()));
        record.setDeletedAt(Timestamps.toOffsetDateTime(file.deletedAt()));
        return record;
    }
}
