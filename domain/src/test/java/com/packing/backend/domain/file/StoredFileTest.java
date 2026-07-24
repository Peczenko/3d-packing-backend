package com.packing.backend.domain.file;

import com.packing.backend.domain.file.event.FileDeleted;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredFileTest {

    private static final Instant NOW = Instant.parse("2026-07-19T10:15:30Z");
    private static final Instant LATER = NOW.plusSeconds(3600);
    private static final UserId OWNER = UserId.generate();
    private static final Checksum CHECKSUM =
            Checksum.ofHex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

    private StoredFile availableFile() {
        return StoredFile.upload(FileId.generate(), OWNER, new FileName("bracket.stl"),
                2_048L, CHECKSUM, NOW);
    }

    @Test
    void uploadStartsAvailableAndDerivesTheKeyFormatAndContentType() {
        FileId id = FileId.generate();

        StoredFile file = StoredFile.upload(id, OWNER, new FileName("bracket.STL"),
                2_048L, CHECKSUM, NOW);

        assertThat(file.status()).isEqualTo(FileStatus.AVAILABLE);
        assertThat(file.storageKey()).isEqualTo(StorageKey.forFile(id));
        assertThat(file.format()).isEqualTo(ModelFormat.STL);
        assertThat(file.contentType()).isEqualTo("model/stl");
        assertThat(file.version()).isEqualTo(StoredFile.INITIAL_VERSION);
        assertThat(file.createdAt()).isEqualTo(NOW);
        assertThat(file.updatedAt()).isEqualTo(NOW);
        assertThat(file.deletedAt()).isNull();
    }

    @Test
    void uploadLeavesTheProjectSlotEmptyUntilProjectsExist() {
        assertThat(availableFile().projectId()).isNull();
    }

    @Test
    void uploadRecordsNoEvent() {
        assertThat(availableFile().domainEvents()).isEmpty();
    }

    @Test
    void uploadRejectsAnEmptyFile() {
        assertThatThrownBy(() -> StoredFile.upload(FileId.generate(), OWNER,
                new FileName("bracket.stl"), 0L, CHECKSUM, NOW))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void uploadRejectsAFileOverTheLimit() {
        assertThatThrownBy(() -> StoredFile.upload(FileId.generate(), OWNER,
                new FileName("bracket.stl"), StoredFile.MAX_SIZE_BYTES + 1, CHECKSUM, NOW))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void uploadAcceptsAFileExactlyAtTheLimit() {
        StoredFile file = StoredFile.upload(FileId.generate(), OWNER,
                new FileName("bracket.stl"), StoredFile.MAX_SIZE_BYTES, CHECKSUM, NOW);

        assertThat(file.sizeBytes()).isEqualTo(StoredFile.MAX_SIZE_BYTES);
    }

    @Test
    void uploadRejectsAnUnsupportedFormat() {
        assertThatThrownBy(() -> StoredFile.upload(FileId.generate(), OWNER,
                new FileName("notes.txt"), 10L, CHECKSUM, NOW))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("Unsupported 3D model format");
    }

    @Test
    void deleteTombstonesTheFileAndRecordsTheEventTheBlobCleanupNeeds() {
        StoredFile file = availableFile();

        file.delete(LATER);

        assertThat(file.isDeleted()).isTrue();
        assertThat(file.status()).isEqualTo(FileStatus.DELETED);
        assertThat(file.deletedAt()).isEqualTo(LATER);
        assertThat(file.updatedAt()).isEqualTo(LATER);
        assertThat(file.domainEvents()).singleElement()
                .isInstanceOfSatisfying(FileDeleted.class, event -> {
                    assertThat(event.fileId()).isEqualTo(file.id());
                    assertThat(event.storageKey()).isEqualTo(file.storageKey());
                    assertThat(event.ownerId()).isEqualTo(OWNER);
                    assertThat(event.occurredAt()).isEqualTo(LATER);
                });
    }

    @Test
    void deleteIsIdempotentAndRecordsOnlyOneEvent() {
        StoredFile file = availableFile();

        file.delete(LATER);
        file.delete(LATER.plusSeconds(60));

        assertThat(file.deletedAt()).isEqualTo(LATER);
        assertThat(file.domainEvents()).hasSize(1);
    }

    @Test
    void pullDomainEventsDrainsTheBuffer() {
        StoredFile file = availableFile();
        file.delete(LATER);

        assertThat(file.pullDomainEvents()).hasSize(1);
        assertThat(file.pullDomainEvents()).isEmpty();
    }

    @Test
    void isOwnedByDistinguishesTheOwnerFromEveryoneElse() {
        StoredFile file = availableFile();

        assertThat(file.isOwnedBy(OWNER)).isTrue();
        assertThat(file.isOwnedBy(UserId.generate())).isFalse();
    }

    @Test
    void markPersistedAdvancesTheOptimisticLock() {
        StoredFile file = availableFile();

        file.markPersisted();

        assertThat(file.version()).isEqualTo(StoredFile.INITIAL_VERSION + 1);
    }

    @Test
    void rehydrateRestoresStateWithoutRecordingEvents() {
        FileId id = FileId.generate();

        StoredFile file = StoredFile.rehydrate(id, OWNER, null, new FileName("bracket.stl"),
                StorageKey.forFile(id), ModelFormat.STL, 2_048L, CHECKSUM,
                FileStatus.DELETED, 7L, NOW, LATER, LATER);

        assertThat(file.version()).isEqualTo(7L);
        assertThat(file.isDeleted()).isTrue();
        assertThat(file.domainEvents()).isEmpty();
    }

    @Test
    void identityIsTheIdAlone() {
        FileId id = FileId.generate();
        StoredFile one = StoredFile.upload(id, OWNER, new FileName("a.stl"), 1L, CHECKSUM, NOW);
        StoredFile other = StoredFile.upload(id, UserId.generate(), new FileName("b.obj"),
                999L, CHECKSUM, LATER);

        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
    }
}
