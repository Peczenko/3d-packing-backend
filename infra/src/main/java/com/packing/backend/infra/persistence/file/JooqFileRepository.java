package com.packing.backend.infra.persistence.file;

import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.shared.ConcurrentUpdateException;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileStatus;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.infra.persistence.shared.SqlConstraintViolationTranslator;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.Files.FILES;

/**
 * No {@code @Transactional} here: transaction boundaries belong to the application services
 * in {@code :core}, and {@code FileApplicationService#upload} is deliberately not
 * transactional, so a save on the upload path commits on its own — that is intended.
 */
@Repository
public class JooqFileRepository implements FileRepository {

    private final DSLContext dsl;

    public JooqFileRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Upsert on the primary key, guarded by the aggregate's version.
     *
     * <p>The {@code WHERE} on the conflict branch matches the <em>stored</em> version, so
     * an update built from a stale read affects zero rows and raises instead of silently
     * overwriting a concurrent change.
     */
    @Override
    public StoredFile save(StoredFile file) {
        long expectedVersion = file.version();
        int affected = constraintTranslatorFor(file).translating(() -> dsl.insertInto(FILES)
                .set(FILES.ID, file.id().value())
                .set(FILES.OWNER_USER_ID, file.ownerId().value())
                .set(FILES.PROJECT_ID, file.projectId())
                .set(FILES.ORIGINAL_FILENAME, file.name().value())
                .set(FILES.STORAGE_KEY, file.storageKey().value())
                .set(FILES.FORMAT, file.format().name())
                .set(FILES.CONTENT_TYPE, file.contentType())
                .set(FILES.SIZE_BYTES, file.sizeBytes())
                .set(FILES.CHECKSUM_SHA256, file.checksum().value())
                .set(FILES.STATUS, file.status().name())
                // Both branches store expectedVersion + 1 so a write always advances the
                // version by exactly one, whether it inserted or updated.
                .set(FILES.VERSION, expectedVersion + 1)
                .set(FILES.CREATED_AT, FileRecordMapper.toOffsetDateTime(file.createdAt()))
                .set(FILES.UPDATED_AT, FileRecordMapper.toOffsetDateTime(file.updatedAt()))
                .set(FILES.DELETED_AT, FileRecordMapper.toOffsetDateTime(file.deletedAt()))
                .onConflict(FILES.ID)
                .doUpdate()
                // id, owner_user_id, storage_key, original_filename, format, content_type,
                // size_bytes, checksum and created_at are immutable in the domain, so they
                // are deliberately absent from the update set. Only the project link and
                // the lifecycle columns can change.
                .set(FILES.PROJECT_ID, file.projectId())
                .set(FILES.STATUS, file.status().name())
                .set(FILES.VERSION, expectedVersion + 1)
                .set(FILES.UPDATED_AT, FileRecordMapper.toOffsetDateTime(file.updatedAt()))
                .set(FILES.DELETED_AT, FileRecordMapper.toOffsetDateTime(file.deletedAt()))
                .where(FILES.VERSION.eq(expectedVersion))
                .execute());

        if (affected == 0) {
            throw new ConcurrentUpdateException(
                    "File " + file.id() + " was modified by another transaction "
                            + "(expected version " + expectedVersion + "). Re-read and retry.");
        }
        file.markPersisted();
        return file;
    }

    @Override
    public Optional<StoredFile> findById(FileId id) {
        return dsl.selectFrom(FILES)
                .where(FILES.ID.eq(id.value()))
                .fetchOptional()
                .map(FileRecordMapper::toDomain);
    }

    /**
     * Ordered newest first with the id as a tiebreak, so paging stays stable when two
     * uploads share a timestamp. Backed by {@code ix_files_owner_created}, scanned
     * backwards.
     */
    @Override
    public List<StoredFile> findAvailableByOwner(UserId ownerId, int offset, int limit) {
        return dsl.selectFrom(FILES)
                .where(FILES.OWNER_USER_ID.eq(ownerId.value())
                        .and(FILES.STATUS.eq(FileStatus.AVAILABLE.name())))
                .orderBy(FILES.CREATED_AT.desc(), FILES.ID.desc())
                .offset(offset)
                .limit(limit)
                .fetch()
                .map(FileRecordMapper::toDomain);
    }

    @Override
    public long countAvailableByOwner(UserId ownerId) {
        return dsl.fetchCount(dsl.selectFrom(FILES)
                .where(FILES.OWNER_USER_ID.eq(ownerId.value())
                        .and(FILES.STATUS.eq(FileStatus.AVAILABLE.name()))));
    }

    /**
     * The storage key is derived from the file id, so a collision means the same id was
     * inserted twice — which the primary key would also catch. Kept because the unique
     * constraint is what guards the key's uniqueness if the naming scheme ever changes.
     * Constraint names come from V2__create_files_table.sql.
     */
    private SqlConstraintViolationTranslator constraintTranslatorFor(StoredFile file) {
        return new SqlConstraintViolationTranslator(Map.of(
                "uq_files_storage_key", () -> new ResourceConflictException(
                        "A file already exists at storage key " + file.storageKey())));
    }
}
