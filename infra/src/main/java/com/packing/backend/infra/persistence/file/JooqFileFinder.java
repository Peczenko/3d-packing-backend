package com.packing.backend.infra.persistence.file;

import com.packing.backend.core.file.FileListCriteria;
import com.packing.backend.core.file.FileView;
import com.packing.backend.core.file.port.out.FileFinder;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.infra.persistence.shared.Paging;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.springframework.stereotype.Repository;
import org.jooq.DSLContext;
import org.jooq.Field;

import java.util.List;
import java.util.Locale;

import static com.packing.backend.infra.persistence.jooq.tables.Files.FILES;
import static com.packing.backend.infra.persistence.shared.JooqConditions.instantRange;
import static org.jooq.impl.DSL.lower;

@Repository
@RequiredArgsConstructor
public class JooqFileFinder implements FileFinder {

    private final DSLContext dsl;

    @Override
    public Page<FileView> listAvailableInProject(ProjectId projectId, FileListCriteria criteria) {
        Condition condition = listCondition(projectId, criteria);

        List<org.jooq.SortField<?>> orderBy = List.of(
                                                      order(primarySort(criteria.sort()), criteria.direction()),
                                                      order(FILES.ID, criteria.direction()));
        return Paging.fetch(dsl,
                            dsl.selectFrom(FILES)
                               .where(condition),
                            orderBy,
                            criteria.page(),
                            record -> FileView.from(FileRecordMapper.toDomain(record)));
    }

    private static Condition listCondition(ProjectId projectId, FileListCriteria criteria) {
        Condition condition = FileQueries.availableIn(projectId);
        if (criteria.search() != null) {
            condition = condition.and(lower(FILES.ORIGINAL_FILENAME)
                                                                    .contains(criteria.search()
                                                                                      .toLowerCase(Locale.ROOT)));
        }
        if (!criteria.formats()
                     .isEmpty()) {
            condition = condition.and(FILES.FORMAT.in(criteria.formats()));
        }
        return condition.and(instantRange(FILES.CREATED_AT, criteria.createdAt()));
    }

    private static Field<?> primarySort(FileListCriteria.SortField sort) {
        return switch (sort) {
            case FILENAME -> lower(FILES.ORIGINAL_FILENAME);
            case FORMAT -> FILES.FORMAT.cast(String.class);
            case SIZE_BYTES -> FILES.SIZE_BYTES;
            case CREATED_AT -> FILES.CREATED_AT;
        };
    }

    private static org.jooq.SortField<?> order(Field<?> field, SortDirection direction) {
        return direction == SortDirection.ASC ? field.asc() : field.desc();
    }

}
