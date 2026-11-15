package com.packing.backend.domain.file;

import com.packing.backend.domain.file.event.FileDeleted;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredFileTest {

    private static final Instant   NOW       = Instant.parse("2026-07-19T10:15:30Z");
    private static final Instant   LATER     = NOW.plusSeconds(3600);
    private static final UserId    OWNER     = UserId.generate();
    private static final ProjectId PROJECT   = ProjectId.generate();
    private static final Checksum  CHECKSUM  = Checksum.ofHex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    private static final Checksum  CONVERTED = Checksum.ofHex("c".repeat(64));

    private static StoredFile uploadStl(FileId id, String name, long sizeBytes) {
        return StoredFile.upload(id,
                                 OWNER,
                                 PROJECT,
                                 new FileName(name),
                                 sizeBytes,
                                 CHECKSUM,
                                 ConvertedObj.of(id, 4_096L, CONVERTED),
                                 NOW);
    }

    private static StoredFile uploadObj(FileId id) {
        return StoredFile.upload(id, OWNER, PROJECT, new FileName("bunny.obj"), 2_048L, CHECKSUM, NOW);
    }

    private StoredFile availableFile() {
        return uploadStl(FileId.generate(), "bracket.stl", 2_048L);
    }

    private static StoredFile legacyStl(FileId id) {
        return StoredFile.rehydrate(id,
                                    OWNER,
                                    PROJECT,
                                    new FileName("bracket.stl"),
                                    StorageKey.forFile(id),
                                    ModelFormat.STL,
                                    2_048L,
                                    CHECKSUM,
                                    null,
                                    FileStatus.AVAILABLE,
                                    1L,
                                    NOW,
                                    NOW,
                                    null);
    }

    @Test
    void uploadStartsAvailableAndDerivesTheKeyFormatAndContentType() {
        FileId id = FileId.generate();

        StoredFile file = uploadStl(id, "bracket.STL", 2_048L);

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
    void uploadRecordsTheOwningProject() {
        StoredFile file = availableFile();

        assertThat(file.projectId()).isEqualTo(PROJECT);
        assertThat(file.belongsTo(PROJECT)).isTrue();
        assertThat(file.belongsTo(ProjectId.generate())).isFalse();
    }

    @Test
    void anObjUploadIsPackedAsItIs() {
        FileId id = FileId.generate();

        StoredFile file = uploadObj(id);

        assertThat(file.convertedObj()).isNull();
        assertThat(file.packingContent()).contains(new ModelContent(StorageKey.forFile(id), ModelFormat.OBJ, CHECKSUM));
        assertThat(file.storageKeys()).containsExactly(StorageKey.forFile(id));
    }

    @Test
    void anUploadInAnotherFormatIsPackedThroughItsConvertedObj() {
        FileId id = FileId.generate();

        StoredFile file = uploadStl(id, "bracket.stl", 2_048L);

        assertThat(file.packingContent()).contains(new ModelContent(StorageKey.forConvertedObj(id), ModelFormat.OBJ, CONVERTED));
        assertThat(file.storageKeys()).containsExactly(StorageKey.forFile(id), StorageKey.forConvertedObj(id));
    }

    @Test
    void aFileStoredBeforeConversionExistedHasNothingToPack() {
        assertThat(legacyStl(FileId.generate()).packingContent()).isEmpty();
    }

    @Test
    void uploadRefusesAnotherFormatWithoutItsConvertedObj() {
        assertThatThrownBy(() -> StoredFile.upload(FileId.generate(),
                                                   OWNER,
                                                   PROJECT,
                                                   new FileName("bracket.stl"),
                                                   2_048L,
                                                   CHECKSUM,
                                                   NOW))
                                                        .isInstanceOf(DomainRuleViolationException.class)
                                                        .hasMessageContaining("must be converted to OBJ");
    }

    @Test
    void uploadRefusesAConvertedCopyOfAnObj() {
        FileId id = FileId.generate();

        assertThatThrownBy(() -> StoredFile.upload(id,
                                                   OWNER,
                                                   PROJECT,
                                                   new FileName("bunny.obj"),
                                                   2_048L,
                                                   CHECKSUM,
                                                   ConvertedObj.of(id, 2_048L, CONVERTED),
                                                   NOW))
                                                        .isInstanceOf(DomainRuleViolationException.class)
                                                        .hasMessageContaining("already OBJ");
    }

    @Test
    void uploadRefusesAConvertedCopyStoredUnderAnotherFilesKey() {
        assertThatThrownBy(() -> StoredFile.upload(FileId.generate(),
                                                   OWNER,
                                                   PROJECT,
                                                   new FileName("bracket.stl"),
                                                   2_048L,
                                                   CHECKSUM,
                                                   ConvertedObj.of(FileId.generate(), 4_096L, CONVERTED),
                                                   NOW))
                                                        .isInstanceOf(DomainRuleViolationException.class)
                                                        .hasMessageContaining("must be stored at");
    }

    @Test
    void uploadRefusesAConvertedObjOverItsLimit() {
        FileId id = FileId.generate();

        assertThatThrownBy(() -> StoredFile.upload(id,
                                                   OWNER,
                                                   PROJECT,
                                                   new FileName("bracket.3mf"),
                                                   2_048L,
                                                   CHECKSUM,
                                                   ConvertedObj.of(id, ConvertedObj.MAX_SIZE_BYTES + 1, CONVERTED),
                                                   NOW))
                                                        .isInstanceOf(DomainRuleViolationException.class)
                                                        .hasMessageContaining("at most " + ConvertedObj.MAX_SIZE_BYTES);
    }

    @Test
    void uploadRefusesStep() {
        FileId id = FileId.generate();

        assertThatThrownBy(() -> StoredFile.upload(id,
                                                   OWNER,
                                                   PROJECT,
                                                   new FileName("housing.step"),
                                                   2_048L,
                                                   CHECKSUM,
                                                   ConvertedObj.of(id, 4_096L, CONVERTED),
                                                   NOW))
                                                        .isInstanceOf(DomainRuleViolationException.class)
                                                        .hasMessageContaining("STL or 3MF");
    }

    @Test
    void renameChangesTheNameAndTheTimestamp() {
        StoredFile file = availableFile();

        file.rename(new FileName("chassis-v2.stl"), LATER);

        assertThat(file.name()).isEqualTo(new FileName("chassis-v2.stl"));
        assertThat(file.updatedAt()).isEqualTo(LATER);
    }

    @Test
    void renameLeavesTheContentAlone() {
        StoredFile file = availableFile();
        StorageKey keyBefore = file.storageKey();
        ConvertedObj convertedBefore = file.convertedObj();

        file.rename(new FileName("chassis-v2.stl"), LATER);

        assertThat(file.storageKey()).isEqualTo(keyBefore);
        assertThat(file.convertedObj()).isEqualTo(convertedBefore);
        assertThat(file.checksum()).isEqualTo(CHECKSUM);
        assertThat(file.sizeBytes()).isEqualTo(2_048L);
    }

    @Test
    void renameRejectsAChangeOfFormat() {
        StoredFile file = availableFile();

        assertThatThrownBy(() -> file.rename(new FileName("bracket.obj"), LATER))
                                                                                 .isInstanceOf(DomainRuleViolationException.class)
                                                                                 .hasMessageContaining("same format");
        assertThat(file.name()).isEqualTo(new FileName("bracket.stl"));
    }

    @Test
    void renameToTheSameNameIsANoOp() {
        StoredFile file = availableFile();

        file.rename(new FileName("bracket.stl"), LATER);

        assertThat(file.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void renameIsRefusedOnATombstone() {
        StoredFile file = availableFile();
        file.delete(LATER);

        assertThatThrownBy(() -> file.rename(new FileName("chassis.stl"), LATER))
                                                                                 .isInstanceOf(DomainRuleViolationException.class)
                                                                                 .hasMessageContaining("deleted");
    }

    @Test
    void uploadRecordsNoEvent() {
        assertThat(availableFile().domainEvents()).isEmpty();
    }

    @Test
    void uploadRejectsAnEmptyFile() {
        assertThatThrownBy(() -> uploadStl(FileId.generate(), "bracket.stl", 0L))
                                                                                 .isInstanceOf(DomainRuleViolationException.class)
                                                                                 .hasMessageContaining("empty");
    }

    @Test
    void uploadRejectsAFileOverTheLimit() {
        assertThatThrownBy(() -> uploadStl(FileId.generate(), "bracket.stl", StoredFile.MAX_SIZE_BYTES + 1))
                                                                                                            .isInstanceOf(DomainRuleViolationException.class)
                                                                                                            .hasMessageContaining("at most");
    }

    @Test
    void uploadAcceptsAFileExactlyAtTheLimit() {
        StoredFile file = uploadStl(FileId.generate(), "bracket.stl", StoredFile.MAX_SIZE_BYTES);

        assertThat(file.sizeBytes()).isEqualTo(StoredFile.MAX_SIZE_BYTES);
    }

    @Test
    void uploadRejectsAnUnsupportedFormat() {
        assertThatThrownBy(() -> StoredFile.upload(FileId.generate(),
                                                   OWNER,
                                                   PROJECT,
                                                   new FileName("notes.txt"),
                                                   10L,
                                                   CHECKSUM,
                                                   NOW))
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
                                           assertThat(event.storageKeys()).containsExactly(file.storageKey(),
                                                                                           file.convertedObj()
                                                                                               .storageKey());
                                           assertThat(event.ownerId()).isEqualTo(OWNER);
                                           assertThat(event.occurredAt()).isEqualTo(LATER);
                                       });
    }

    @Test
    void deletingAnObjUploadCleansUpItsOneBlob() {
        StoredFile file = uploadObj(FileId.generate());

        file.delete(LATER);

        assertThat(file.domainEvents()).singleElement()
                                       .isInstanceOfSatisfying(FileDeleted.class,
                                                               event -> assertThat(event.storageKeys()).containsExactly(file.storageKey()));
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
    void aFileIsAvailableOnlyInItsOwnProjectAndOnlyUntilDeleted() {
        StoredFile file = availableFile();

        assertThat(file.isAvailableIn(PROJECT)).isTrue();
        assertThat(file.isAvailableIn(ProjectId.generate())).isFalse();

        file.delete(NOW);

        assertThat(file.isAvailableIn(PROJECT)).isFalse();
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
        ConvertedObj converted = ConvertedObj.of(id, 4_096L, CONVERTED);

        StoredFile file = StoredFile.rehydrate(id,
                                               OWNER,
                                               PROJECT,
                                               new FileName("bracket.stl"),
                                               StorageKey.forFile(id),
                                               ModelFormat.STL,
                                               2_048L,
                                               CHECKSUM,
                                               converted,
                                               FileStatus.DELETED,
                                               7L,
                                               NOW,
                                               LATER,
                                               LATER);

        assertThat(file.version()).isEqualTo(7L);
        assertThat(file.convertedObj()).isEqualTo(converted);
        assertThat(file.isDeleted()).isTrue();
        assertThat(file.domainEvents()).isEmpty();
    }

    @Test
    void identityIsTheIdAlone() {
        FileId id = FileId.generate();
        StoredFile one = uploadStl(id, "a.stl", 1L);
        StoredFile other = StoredFile.upload(id,
                                             UserId.generate(),
                                             PROJECT,
                                             new FileName("b.obj"),
                                             999L,
                                             CHECKSUM,
                                             LATER);

        assertThat(one).isEqualTo(other)
                       .hasSameHashCodeAs(other);
    }
}
