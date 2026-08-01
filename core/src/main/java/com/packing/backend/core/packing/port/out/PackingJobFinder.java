package com.packing.backend.core.packing.port.out;

import com.packing.backend.core.packing.PackingJobView;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.project.ProjectId;

import java.util.List;
import java.util.Optional;

public interface PackingJobFinder {

    Page<PackingJobView> listInProject(ProjectId projectId, PageRequest page);

    Optional<PackingJobView> detailInProject(ProjectId projectId, PackingJobId jobId);

    List<PackingJobId> findUndispatched(int limit);

    List<PackingJobId> findRunning(int limit);
}
