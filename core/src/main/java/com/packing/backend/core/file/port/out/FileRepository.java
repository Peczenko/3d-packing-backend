package com.packing.backend.core.file.port.out;

import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.user.UserId;

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

    /** Returns deleted files too — the caller decides what a tombstone means to it. */
    Optional<StoredFile> findById(FileId id);

    /** Newest first, tombstones excluded. */
    List<StoredFile> findAvailableByOwner(UserId ownerId, int offset, int limit);

    long countAvailableByOwner(UserId ownerId);
}
