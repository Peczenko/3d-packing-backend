package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.project.port.out.ProjectAccessLookup.ProjectAccess;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.Username;
import com.packing.backend.infra.TestcontainersConfiguration;
import com.packing.backend.infra.persistence.user.JooqUserRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three filters in this lookup are the port's contract rather than optimisations, so
 * each of them gets a test: an inactive account, a deleted project and a non-member must all
 * be indistinguishable from a project that never existed.
 */
@JooqTest
@Import(TestcontainersConfiguration.class)
class JooqProjectAccessLookupIT {

    private static final FirebaseUid OWNER_UID = new FirebaseUid("uid-owner");
    private static final FirebaseUid OUTSIDER_UID = new FirebaseUid("uid-outsider");

    @Autowired
    private DSLContext dsl;

    private User owner;
    private User outsider;
    private Project project;

    private JooqProjectAccessLookup lookup() {
        return new JooqProjectAccessLookup(dsl);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @BeforeEach
    void createFixtures() {
        owner = persistUser(OWNER_UID, "owner");
        outsider = persistUser(OUTSIDER_UID, "outsider");
        project = Project.create(new ProjectName("Chassis"), owner.id(), now());
        new JooqProjectRepository(dsl).save(project);
    }

    private User persistUser(FirebaseUid uid, String username) {
        User user = User.register(uid, new Email(username + "@example.com"),
                new Username(username), username, now());
        new JooqUserRepository(dsl).save(user);
        return user;
    }

    @Test
    void resolvesTheCallerAndTheirPermissionInOneLookup() {
        Optional<ProjectAccess> access = lookup().findAccess(OWNER_UID, project.id());

        assertThat(access).hasValueSatisfying(found -> {
            assertThat(found.userId()).isEqualTo(owner.id());
            assertThat(found.projectId()).isEqualTo(project.id());
            assertThat(found.status()).isEqualTo(ProjectStatus.ACTIVE);
            assertThat(found.permission()).isEqualTo(ProjectPermission.OWNER);
        });
    }

    @Test
    void reportsTheStoredPermissionForAPlainMember() {
        project.grantAccess(outsider.id(), ProjectPermission.READ, owner.id(), now());
        new JooqProjectRepository(dsl).save(project);

        assertThat(lookup().findAccess(OUTSIDER_UID, project.id()))
                .hasValueSatisfying(found ->
                        assertThat(found.permission()).isEqualTo(ProjectPermission.READ));
    }

    /** DISABLED still resolves: it is a read-only archive, not a hidden project. */
    @Test
    void resolvesADisabledProjectSoThatReadsKeepWorking() {
        project.disable(now());
        new JooqProjectRepository(dsl).save(project);

        assertThat(lookup().findAccess(OWNER_UID, project.id()))
                .hasValueSatisfying(found ->
                        assertThat(found.status()).isEqualTo(ProjectStatus.DISABLED));
    }

    @Test
    void isEmptyForANonMember() {
        assertThat(lookup().findAccess(OUTSIDER_UID, project.id())).isEmpty();
    }

    @Test
    void isEmptyForADeletedProject() {
        project.delete(now());
        new JooqProjectRepository(dsl).save(project);

        assertThat(lookup().findAccess(OWNER_UID, project.id())).isEmpty();
    }

    @Test
    void isEmptyForAnUnknownProject() {
        assertThat(lookup().findAccess(OWNER_UID, ProjectId.generate())).isEmpty();
    }

    @Test
    void isEmptyForAnIdentityWithNoProfile() {
        assertThat(lookup().findAccess(new FirebaseUid("uid-nobody"), project.id())).isEmpty();
    }

    @Test
    void isEmptyForADisabledAccountEvenThoughTheMembershipRemains() {
        owner.disable(now());
        new JooqUserRepository(dsl).save(owner);

        assertThat(lookup().findAccess(OWNER_UID, project.id())).isEmpty();
    }

    @Test
    void isEmptyForADeletedAccountEvenThoughTheMembershipRemains() {
        owner.delete(now());
        new JooqUserRepository(dsl).save(owner);

        assertThat(lookup().findAccess(OWNER_UID, project.id())).isEmpty();
    }
}
