package com.packing.backend.core.project.port.out;

import com.packing.backend.core.project.ProjectSummaryView;
import com.packing.backend.core.project.ProjectView;
import com.packing.backend.core.project.ProjectListCriteria;
import com.packing.backend.core.shared.Page;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;

import java.util.Optional;

public interface ProjectFinder {

    Page<ProjectSummaryView> listForMember(UserId caller, ProjectListCriteria criteria);

    Optional<ProjectView> detailFor(UserId caller, ProjectId projectId);
}
