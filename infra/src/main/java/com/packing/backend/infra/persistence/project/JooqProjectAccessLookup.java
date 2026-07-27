package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.project.port.out.ProjectAccessLookup;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.ProjectMembers.PROJECT_MEMBERS;
import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;
import static com.packing.backend.infra.persistence.jooq.tables.Users.USERS;

/**
 * Resolves the caller and their permission in one join, on the path taken by every file
 * request. The three filters are the port's contract, not optimisations: an inactive
 * account, a deleted project and a non-member must all be indistinguishable from a project
 * that never existed.
 */
@Repository
@RequiredArgsConstructor
public class JooqProjectAccessLookup implements ProjectAccessLookup {

    private final DSLContext dsl;


    @Override
    public Optional<ProjectAccess> findAccess(FirebaseUid firebaseUid, ProjectId projectId) {
        return dsl.select(USERS.ID, PROJECTS.STATUS, PROJECT_MEMBERS.PERMISSION)
                .from(PROJECT_MEMBERS)
                .join(USERS).on(USERS.ID.eq(PROJECT_MEMBERS.USER_ID))
                .join(PROJECTS).on(PROJECTS.ID.eq(PROJECT_MEMBERS.PROJECT_ID))
                .where(USERS.FIREBASE_UID.eq(firebaseUid.value())
                        .and(USERS.STATUS.eq(UserStatus.ACTIVE.name()))
                        .and(PROJECT_MEMBERS.PROJECT_ID.eq(projectId.value()))
                        .and(PROJECTS.STATUS.ne(ProjectStatus.DELETED.name())))
                .fetchOptional()
                .map(record -> new ProjectAccess(
                        new UserId(record.get(USERS.ID)),
                        projectId,
                        ProjectStatus.valueOf(record.get(PROJECTS.STATUS)),
                        ProjectPermission.valueOf(record.get(PROJECT_MEMBERS.PERMISSION))));
    }
}
