package com.packing.backend.infra.persistence.file;

import com.packing.backend.core.shared.ConcurrentUpdateException;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.FileStatus;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;
import com.packing.backend.infra.TestcontainersConfiguration;
import com.packing.backend.infra.persistence.user.JooqUserRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.Files.FILES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The compensating control for generating offline: the generator reverse engineers the
 * migrations through an in-memory H2 database, so only executing against real PostgreSQL
 * proves the two agree.
 */
@JooqTest
@Import(TestcontainersConfiguration.class)
class JooqFileRepositoryIT {

    private static final Checksum CHECKSUM =
            Checksum.ofHex("d3a15aa3cd30cc79123d6a50d2809ed794a452e67fa857bbc7ac343cbfca9971");

    @Autowired
    private DSLContext dsl;

    private UserId owner;

    private JooqFileRepository repository() {
        return new JooqFileRepository(dsl);
    }

    @BeforeEach
    void createOwner() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        User user = User.register(new FirebaseUid("uid-owner"), new Email("owner@example.com"),
                new Username("owner"), "Owner", now);
        new JooqUserRepository(dsl).save(user);
        owner = user.id();
    }

    private StoredFile newFile(String filename) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return StoredFile.upload(FileId.generate(), owner, new FileName(filename),
                2_048L, CHECKSUM, now);
    }

    @Test
    void savesAndReadsBackEveryField() {
        StoredFile saved = newFile("bracket.stl");
        repository().save(saved);

        Optional<StoredFile> found = repository().findById(saved.id());

        assertThat(found).hasValueSatisfying(file -> {
            assertThat(file.id()).isEqualTo(saved.id());
            assertThat(file.ownerId()).isEqualTo(owner);
            assertThat(file.projectId()).isNull();
            assertThat(file.name()).isEqualTo(new FileName("bracket.stl"));
            assertThat(file.storageKey()).isEqualTo(StorageKey.forFile(saved.id()));
            assertThat(file.format()).isEqualTo(ModelFormat.STL);
            assertThat(file.contentType()).isEqualTo("model/stl");
            assertThat(file.sizeBytes()).isEqualTo(2_048L);
            assertThat(file.checksum()).isEqualTo(CHECKSUM);
            assertThat(file.status()).isEqualTo(FileStatus.AVAILABLE);
            assertThat(file.version()).isEqualTo(StoredFile.INITIAL_VERSION + 1);
            assertThat(file.createdAt()).isEqualTo(saved.createdAt());
            assertThat(file.updatedAt()).isEqualTo(saved.updatedAt());
            assertThat(file.deletedAt()).isNull();
        });
    }

    @Test
    void findByIdIsEmptyForAnUnknownId() {
        assertThat(repository().findById(FileId.generate())).isEmpty();
    }

    @Test
    void aStaleWriteIsRejectedRatherThanClobberingAConcurrentChange() {
        StoredFile file = newFile("bracket.stl");
        repository().save(file);

        StoredFile stale = repository().findById(file.id()).orElseThrow();
        repository().save(stale);

        file.delete(Instant.now().truncatedTo(ChronoUnit.MICROS));
        assertThatThrownBy(() -> repository().save(file))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessageContaining(file.id().toString());
    }

    @Test
    void savingTwiceAdvancesTheVersionByExactlyOneEachTime() {
        StoredFile file = newFile("bracket.stl");

        repository().save(file);
        assertThat(file.version()).isEqualTo(1L);

        file.delete(Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository().save(file);
        assertThat(file.version()).isEqualTo(2L);
        assertThat(repository().findById(file.id()).orElseThrow().version()).isEqualTo(2L);
    }

    @Test
    void deletingIsPersistedAsATombstoneRatherThanARowRemoval() {
        StoredFile file = newFile("bracket.stl");
        repository().save(file);
        Instant deletedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        file.delete(deletedAt);
        repository().save(file);

        assertThat(repository().findById(file.id())).hasValueSatisfying(found -> {
            assertThat(found.status()).isEqualTo(FileStatus.DELETED);
            assertThat(found.deletedAt()).isEqualTo(deletedAt);
            assertThat(found.storageKey()).isEqualTo(file.storageKey());
        });
    }

    @Test
    void listingExcludesTombstonesAndReturnsNewestFirst() {
        StoredFile older = newFile("older.stl");
        repository().save(older);
        StoredFile newer = newFile("newer.stl");
        repository().save(newer);
        StoredFile removed = newFile("removed.stl");
        repository().save(removed);
        removed.delete(Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository().save(removed);

        List<StoredFile> found = repository().findAvailableByOwner(owner, 0, 10);

        assertThat(found).extracting(file -> file.name().value())
                .containsExactly("newer.stl", "older.stl");
        assertThat(repository().countAvailableByOwner(owner)).isEqualTo(2L);
    }

    @Test
    void listingPagesWithOffsetAndLimit() {
        for (int i = 0; i < 5; i++) {
            repository().save(newFile("part-" + i + ".stl"));
        }

        assertThat(repository().findAvailableByOwner(owner, 0, 2)).hasSize(2);
        assertThat(repository().findAvailableByOwner(owner, 4, 2)).hasSize(1);
        assertThat(repository().findAvailableByOwner(owner, 10, 2)).isEmpty();
        assertThat(repository().countAvailableByOwner(owner)).isEqualTo(5L);
    }

    @Test
    void listingIsScopedToTheOwner() {
        repository().save(newFile("mine.stl"));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        User other = User.register(new FirebaseUid("uid-other"), new Email("other@example.com"),
                new Username("other"), "Other", now);
        new JooqUserRepository(dsl).save(other);

        assertThat(repository().findAvailableByOwner(other.id(), 0, 10)).isEmpty();
        assertThat(repository().countAvailableByOwner(other.id())).isZero();
    }

    @Test
    void theOwnerForeignKeyRejectsAFileWithNoSuchUser() {
        StoredFile orphan = StoredFile.upload(FileId.generate(), UserId.generate(),
                new FileName("bracket.stl"), 2_048L, CHECKSUM,
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        assertThatThrownBy(() -> repository().save(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_files_owner_user_id");
    }

    @Test
    void theStatusCheckConstraintRejectsAValueOutsideTheEnum() {
        StoredFile file = newFile("bracket.stl");
        repository().save(file);

        assertThatThrownBy(() -> dsl.update(FILES)
                .set(FILES.STATUS, "BOGUS")
                .where(FILES.ID.eq(file.id().value()))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_files_status");
    }
}
