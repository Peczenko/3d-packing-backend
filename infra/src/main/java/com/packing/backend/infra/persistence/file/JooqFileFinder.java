package com.packing.backend.infra.persistence.file;

import com.packing.backend.core.file.FileView;
import com.packing.backend.core.file.port.out.FileFinder;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.infra.persistence.shared.Paging;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.jooq.DSLContext;

import static com.packing.backend.infra.persistence.jooq.tables.Files.FILES;

@Repository
@RequiredArgsConstructor
public class JooqFileFinder implements FileFinder {

    private final DSLContext dsl;

    @Override
    public Page<FileView> listAvailableInProject(ProjectId projectId, PageRequest page) {
        return Paging.fetch(dsl,
                dsl.selectFrom(FILES)
                        .where(FileQueries.availableIn(projectId))
                        .orderBy(FILES.CREATED_AT.desc(), FILES.ID.desc()),
                page,
                record -> FileView.from(FileRecordMapper.toDomain(record)));
    }
}
