package com.packing.backend.core.project.port.out;

import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository {

    /**
     * Writes the project and reconciles its member rows, guarded on the version it was read
     * at.
     *
     * @throws com.packing.backend.core.shared.ConcurrentUpdateException if the stored
     *         version has moved on, meaning this write is based on a stale read
     */
    Project save(Project project);

    /** Returns deleted projects too — the caller decides what a tombstone means to it. */
    Optional<Project> findById(ProjectId id);

    /** Newest first, tombstones excluded. */
    List<Project> findByMember(UserId userId, int offset, int limit);

    long countByMember(UserId userId);
}
