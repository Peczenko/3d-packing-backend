package com.packing.backend.core.project.port.out;

import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;

import java.util.Optional;

public interface ProjectRepository {

    Project save(Project project);

    Optional<Project> findById(ProjectId id);
}
