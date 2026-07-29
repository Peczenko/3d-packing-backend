package com.packing.backend.infra.persistence.file;

import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileStatus;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.infra.persistence.shared.AggregateWriter;
import com.packing.backend.infra.persistence.shared.SqlConstraintViolationTranslator;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
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
@RequiredArgsConstructor
public class JooqFileRepository implements FileRepository {

    private final DSLContext dsl;
    private final AggregateWriter writer;


    @Override
    public StoredFile save(StoredFile file) {
        constraintTranslatorFor(file).translating(() ->
                writer.upsert(FileRecordMapper.TABLE, FileRecordMapper.toRecord(file),
                        file.version()));
        file.markPersisted();
        return file;
    }

    /**
     * Deliberately a loop over {@link #save}, not a bulk {@code UPDATE}: each file carries
     * its own version, and collapsing them into one statement would drop the optimistic lock
     * exactly where the cascade is most likely to race a concurrent upload.
     */
    @Override
    public List<StoredFile> saveAll(List<StoredFile> files) {
        return files.stream().map(this::save).toList();
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
     * uploads share a timestamp. Backed by {@code ix_files_project_created}, scanned
     * backwards.
     */
    @Override
    public List<StoredFile> findAvailableByProject(ProjectId projectId, int offset, int limit) {
        return dsl.selectFrom(FILES)
                .where(availableIn(projectId))
                .orderBy(FILES.CREATED_AT.desc(), FILES.ID.desc())
                .offset(offset)
                .limit(limit)
                .fetch()
                .map(FileRecordMapper::toDomain);
    }

    @Override
    public long countAvailableByProject(ProjectId projectId) {
        return dsl.fetchCount(dsl.selectFrom(FILES).where(availableIn(projectId)));
    }

    @Override
    public List<StoredFile> findAllAvailableByProject(ProjectId projectId) {
        return dsl.selectFrom(FILES)
                .where(availableIn(projectId))
                .orderBy(FILES.CREATED_AT.desc(), FILES.ID.desc())
                .fetch()
                .map(FileRecordMapper::toDomain);
    }

    private Condition availableIn(ProjectId projectId) {
        return FILES.PROJECT_ID.eq(projectId.value())
                .and(FILES.STATUS.eq(FileStatus.AVAILABLE.name()));
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
