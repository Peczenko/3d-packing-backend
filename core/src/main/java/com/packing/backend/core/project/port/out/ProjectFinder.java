package com.packing.backend.core.project.port.out;

import com.packing.backend.core.project.ProjectSummaryView;
import com.packing.backend.core.project.ProjectView;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;

import java.util.Optional;

/**
 * Project reads that do not need the aggregate. Every method joins through the caller's
 * membership row, so a non-member sees nothing rather than a 403 — the same rule
 * {@code Project.requireAccess} enforces on the write side.
 */
public interface ProjectFinder {

    /** Newest first, tombstones excluded. */
    Page<ProjectSummaryView> listForMember(UserId caller, PageRequest page);

    /** Empty when the project does not exist, is a tombstone, or the caller is not a member. */
    Optional<ProjectView> detailFor(UserId caller, ProjectId projectId);
}
