package com.packing.backend.domain.project;

import com.packing.backend.domain.shared.ResourceNotFoundException;

public class ProjectNotFoundException extends ResourceNotFoundException {

    private ProjectNotFoundException(String message) {
        super(message);
    }

    public static ProjectNotFoundException byId(ProjectId id) {
        return new ProjectNotFoundException("No project found with id " + id);
    }
}
