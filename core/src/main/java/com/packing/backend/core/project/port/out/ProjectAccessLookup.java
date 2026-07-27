package com.packing.backend.core.project.port.out;

import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.shared.PermissionDeniedException;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;

import java.util.Optional;

/**
 * The narrow read behind every file operation.
 *
 * <p>Deliberately not {@code ProjectRepository.findById}: a file download only needs to know
 * the caller's level, and loading the aggregate would fetch every member row of the project
 * to answer a question about one of them. This resolves the caller and their permission in a
 * single indexed join.
 */
public interface ProjectAccessLookup {

    /**
     * Empty when the caller has no active profile, when the project is deleted, and when the
     * caller is simply not a member. All three collapse into the same 404, so a project id
     * cannot be probed for existence.
     */
    Optional<ProjectAccess> findAccess(FirebaseUid firebaseUid, ProjectId projectId);

    record ProjectAccess(UserId userId,
                         ProjectId projectId,
                         ProjectStatus status,
                         ProjectPermission permission) {

        /** Mirrors {@code Project.requireAccess} for callers that never load the aggregate. */
        public ProjectAccess requireAtLeast(ProjectPermission required) {
            if (!permission.allows(required)) {
                throw new PermissionDeniedException(
                        "This action requires " + required + " permission on project " + projectId);
            }
            return this;
        }

        /** Mirrors {@code Project.requireWritable}: DISABLED is a read-only archive. */
        public ProjectAccess requireWritable() {
            if (status != ProjectStatus.ACTIVE) {
                throw new ResourceConflictException(
                        "Project " + projectId + " is " + status.name().toLowerCase()
                                + " and cannot be modified");
            }
            return this;
        }
    }
}
