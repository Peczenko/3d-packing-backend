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
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FileRecordMapperTest {

    private static final Instant NOW = Instant.parse("2026-07-28T10:15:30Z");

    @Test
    void roundTripsEveryColumn() {
        FileId id = FileId.generate();
        UserId owner = UserId.generate();
        ProjectId project = ProjectId.generate();
        StoredFile file = StoredFile.rehydrate(id,
                                               owner,
                                               project,
                                               new FileName("part.stl"),
                                               StorageKey.forFile(id),
                                               ModelFormat.STL,
                                               2_048L,
                                               Checksum.ofHex("a".repeat(64)),
                                               FileStatus.AVAILABLE,
                                               4L,
                                               NOW,
                                               NOW.plusSeconds(1),
                                               null);

        StoredFile result = FileRecordMapper.toDomain(FileRecordMapper.toRecord(file));

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.ownerId()).isEqualTo(owner);
        assertThat(result.projectId()).isEqualTo(project);
        assertThat(result.name()).isEqualTo(new FileName("part.stl"));
        assertThat(result.storageKey()).isEqualTo(file.storageKey());
        assertThat(result.format()).isEqualTo(ModelFormat.STL);
        assertThat(result.contentType()).isEqualTo(file.contentType());
        assertThat(result.sizeBytes()).isEqualTo(2_048L);
        assertThat(result.checksum()).isEqualTo(file.checksum());
        assertThat(result.status()).isEqualTo(FileStatus.AVAILABLE);
        assertThat(result.version()).isEqualTo(4L);
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(result.updatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(result.deletedAt()).isNull();
    }

    @Test
    void roundTripsADeletedFilesTombstone() {
        FileId id = FileId.generate();
        StoredFile file = StoredFile.rehydrate(id,
                                               UserId.generate(),
                                               ProjectId.generate(),
                                               new FileName("gone.stl"),
                                               StorageKey.forFile(id),
                                               ModelFormat.STL,
                                               1L,
                                               Checksum.ofHex("b".repeat(64)),
                                               FileStatus.DELETED,
                                               2L,
                                               NOW,
                                               NOW,
                                               NOW.plusSeconds(9));

        StoredFile result = FileRecordMapper.toDomain(FileRecordMapper.toRecord(file));

        assertThat(result.status()).isEqualTo(FileStatus.DELETED);
        assertThat(result.deletedAt()).isEqualTo(NOW.plusSeconds(9));
    }
}
