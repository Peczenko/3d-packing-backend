package com.packing.backend.infra.persistence.file;

import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.FileStatus;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.infra.persistence.jooq.tables.records.FilesRecord;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * jOOQ surfaces {@code timestamp with time zone} as {@link OffsetDateTime}, while the
 * domain speaks {@link Instant}; everything is normalised to UTC on the way out.
 */
final class FileRecordMapper {

    private FileRecordMapper() {
    }

    static StoredFile toDomain(FilesRecord record) {
        return StoredFile.rehydrate(
                new FileId(record.getId()),
                new UserId(record.getOwnerUserId()),
                record.getProjectId(),
                new FileName(record.getOriginalFilename()),
                new StorageKey(record.getStorageKey()),
                ModelFormat.valueOf(record.getFormat()),
                record.getSizeBytes(),
                Checksum.ofHex(record.getChecksumSha256()),
                FileStatus.valueOf(record.getStatus()),
                record.getVersion(),
                toInstant(record.getCreatedAt()),
                toInstant(record.getUpdatedAt()),
                toInstant(record.getDeletedAt()));
    }

    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
