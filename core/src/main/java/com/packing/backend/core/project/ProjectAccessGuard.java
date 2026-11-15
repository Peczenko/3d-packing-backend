package com.packing.backend.core.project;

import com.packing.backend.core.project.port.out.ProjectAccessLookup;
import com.packing.backend.core.project.port.out.ProjectAccessLookup.ProjectAccess;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectNotFoundException;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.user.FirebaseUid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectAccessGuard {

    private final ProjectAccessLookup lookup;

    public ProjectAccess require(String firebaseUid, UUID projectId, ProjectPermission required) {
        ProjectId id = new ProjectId(projectId);
        return lookup.findAccess(new FirebaseUid(firebaseUid), id)
                     .orElseThrow(() -> ProjectNotFoundException.byId(id))
                     .requireAtLeast(required);
    }

    public ProjectAccess requireWritable(String firebaseUid, UUID projectId) {
        return require(firebaseUid, projectId, ProjectPermission.WRITE).requireWritable();
    }
}
