package com.packing.backend.core.file.port.out;

import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.project.ProjectId;

import java.util.List;
import java.util.Optional;

public interface FileRepository {

    /**
     * Inserts or updates the aggregate, guarded on the version it was read at.
     *
     * @throws com.packing.backend.core.shared.ConcurrentUpdateException if the stored
     *         version has moved on, meaning this write is based on a stale read
     */
    StoredFile save(StoredFile file);

    /**
     * Writes several aggregates in one batch, each guarded on its own version. Used by the
     * project deletion cascade.
     */
    List<StoredFile> saveAll(List<StoredFile> files);

    /** Returns deleted files too — the caller decides what a tombstone means to it. */
    Optional<StoredFile> findById(FileId id);

    /** Unpaged, for the deletion cascade, which has to see every file exactly once. */
    List<StoredFile> findAllAvailableByProject(ProjectId projectId);
}
