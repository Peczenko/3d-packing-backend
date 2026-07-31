package com.packing.backend.infra.persistence.file;

import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.infra.persistence.jooq.tables.records.FilesRecord;
import com.packing.backend.infra.persistence.shared.AggregateTable;
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
                record.getId(),
                record.getOwnerUserId(),
                record.getProjectId(),
                new FileName(record.getOriginalFilename()),
                new StorageKey(record.getStorageKey()),
                record.getFormat(),
                record.getSizeBytes(),
                Checksum.ofHex(record.getChecksumSha256()),
                record.getStatus(),
                record.getVersion(),
                record.getCreatedAt(),
                record.getUpdatedAt(),
                record.getDeletedAt());
    }

    static FilesRecord toRecord(StoredFile file) {
        FilesRecord record = new FilesRecord();
        record.setId(file.id());
        record.setOwnerUserId(file.ownerId());
        record.setProjectId(file.projectId());
        record.setOriginalFilename(file.name().value());
        record.setStorageKey(file.storageKey().value());
        record.setFormat(file.format());
        record.setContentType(file.contentType());
        record.setSizeBytes(file.sizeBytes());
        record.setChecksumSha256(file.checksum().value());
        record.setStatus(file.status());
        record.setVersion(file.version());
        record.setCreatedAt(file.createdAt());
        record.setUpdatedAt(file.updatedAt());
        record.setDeletedAt(file.deletedAt());
        return record;
    }
}
