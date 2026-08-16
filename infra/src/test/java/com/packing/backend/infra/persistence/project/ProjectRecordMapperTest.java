package com.packing.backend.infra.persistence.project;

import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectMember;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectRecordMapperTest {

    private static final Instant NOW = Instant.parse("2026-07-28T10:15:30Z");

    @Test
    void roundTripsEveryProjectColumn() {
        UserId creator = UserId.generate();
        Project project = Project.rehydrate(ProjectId.generate(),
                                            new ProjectName("Chassis"),
                                            creator,
                                            ProjectStatus.DISABLED,
                                            3L,
                                            NOW,
                                            NOW.plusSeconds(1),
                                            null,
                                            List.of(new ProjectMember(creator, ProjectPermission.OWNER, creator, NOW)));

        Project result = ProjectRecordMapper.toDomain(
                                                      ProjectRecordMapper.toRecord(project),
                                                      project.members());

        assertThat(result.id()).isEqualTo(project.id());
        assertThat(result.name()).isEqualTo(new ProjectName("Chassis"));
        assertThat(result.createdBy()).isEqualTo(creator);
        assertThat(result.status()).isEqualTo(ProjectStatus.DISABLED);
        assertThat(result.version()).isEqualTo(3L);
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(result.updatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(result.deletedAt()).isNull();
    }

    @Test
    void roundTripsAMemberRow() {
        ProjectId projectId = ProjectId.generate();
        UserId user = UserId.generate();
        UserId addedBy = UserId.generate();
        ProjectMember member = new ProjectMember(user, ProjectPermission.WRITE, addedBy, NOW);

        ProjectMember result = ProjectRecordMapper.toDomain(
                                                            ProjectRecordMapper.toRecord(projectId, member));

        assertThat(result.userId()).isEqualTo(user);
        assertThat(result.permission()).isEqualTo(ProjectPermission.WRITE);
        assertThat(result.addedBy()).isEqualTo(addedBy);
        assertThat(result.addedAt()).isEqualTo(NOW);
    }

    @Test
    void roundTripsADeletedProjectsTombstone() {
        UserId creator = UserId.generate();
        Project project = Project.rehydrate(ProjectId.generate(),
                                            new ProjectName("Gone"),
                                            creator,
                                            ProjectStatus.DELETED,
                                            9L,
                                            NOW,
                                            NOW,
                                            NOW.plusSeconds(5),
                                            List.of());

        Project result = ProjectRecordMapper.toDomain(
                                                      ProjectRecordMapper.toRecord(project),
                                                      List.of());

        assertThat(result.status()).isEqualTo(ProjectStatus.DELETED);
        assertThat(result.deletedAt()).isEqualTo(NOW.plusSeconds(5));
    }
}
