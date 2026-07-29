package com.packing.backend.infra.persistence.file;

import com.packing.backend.domain.file.FileStatus;
import com.packing.backend.domain.project.ProjectId;
import org.jooq.Condition;

import static com.packing.backend.infra.persistence.jooq.tables.Files.FILES;

/** Deletion is a tombstone, so every read has to exclude it explicitly. */
final class FileQueries {

    private FileQueries() {
    }

    static Condition availableIn(ProjectId projectId) {
        return FILES.PROJECT_ID.eq(projectId.value())
                .and(FILES.STATUS.eq(FileStatus.AVAILABLE.name()));
    }
}
