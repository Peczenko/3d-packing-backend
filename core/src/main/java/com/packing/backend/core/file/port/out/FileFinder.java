package com.packing.backend.core.file.port.out;

import com.packing.backend.core.file.FileView;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.domain.project.ProjectId;

/**
 * File reads for a project the caller has already been authorised against — this port does no
 * permission checking of its own, unlike {@code ProjectFinder}.
 */
public interface FileFinder {

    /** Newest first, tombstones excluded. */
    Page<FileView> listAvailableInProject(ProjectId projectId, PageRequest page);
}
