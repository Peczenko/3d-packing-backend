package com.packing.backend.infra.persistence.file;

import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.infra.persistence.shared.AggregateWriter;
import com.packing.backend.infra.persistence.shared.SqlConstraintViolationTranslator;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.Files.FILES;

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

    @Override
    public List<StoredFile> saveAll(List<StoredFile> files) {
        return files.stream().map(this::save).toList();
    }

    @Override
    public Optional<StoredFile> findById(FileId id) {
        return dsl.selectFrom(FILES)
                .where(FILES.ID.eq(id))
                .fetchOptional()
                .map(FileRecordMapper::toDomain);
    }

    @Override
    public List<StoredFile> findAllAvailableByProject(ProjectId projectId) {
        return dsl.selectFrom(FILES)
                .where(FileQueries.availableIn(projectId))
                .orderBy(FILES.CREATED_AT.desc(), FILES.ID.desc())
                .fetch()
                .map(FileRecordMapper::toDomain);
    }

    private SqlConstraintViolationTranslator constraintTranslatorFor(StoredFile file) {
        return new SqlConstraintViolationTranslator(Map.of(
                "uq_files_storage_key", () -> new ResourceConflictException(
                        "A file already exists at storage key " + file.storageKey())));
    }
}
