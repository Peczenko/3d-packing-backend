package com.packing.backend.core.project.port.out;

import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.shared.PermissionDeniedException;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;

import java.util.Optional;

public interface ProjectAccessLookup {

    Optional<ProjectAccess> findAccess(FirebaseUid firebaseUid, ProjectId projectId);

    record ProjectAccess(UserId userId,
            ProjectId projectId,
            ProjectStatus status,
            ProjectPermission permission) {

        public ProjectAccess requireAtLeast(ProjectPermission required) {
            if (!permission.allows(required)) {
                throw new PermissionDeniedException(
                                                    "This action requires " + required + " permission on project " + projectId);
            }
            return this;
        }

        public ProjectAccess requireWritable() {
            if (status != ProjectStatus.ACTIVE) {
                throw new ResourceConflictException(
                                                    "Project " + projectId + " is " + status.name()
                                                                                            .toLowerCase()
                                                            + " and cannot be modified");
            }
            return this;
        }
    }
}
