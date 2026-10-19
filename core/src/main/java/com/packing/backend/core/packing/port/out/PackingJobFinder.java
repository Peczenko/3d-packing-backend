package com.packing.backend.core.packing.port.out;

import com.packing.backend.core.packing.PackingJobListCriteria;
import com.packing.backend.core.packing.PackingJobView;
import com.packing.backend.core.shared.Page;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;

import java.util.List;
import java.util.Optional;

public interface PackingJobFinder {

    Page<PackingJobView> listInProject(ProjectId projectId, PackingJobListCriteria criteria);

    Optional<PackingJobView> detailInProject(ProjectId projectId, PackingJobId jobId);

    Optional<PackingJobView> latestOwnInProject(ProjectId projectId, UserId userId);

    Optional<PackingJobView> latestInProject(ProjectId projectId);

    List<PackingJobId> findUndispatched(int limit);
}
