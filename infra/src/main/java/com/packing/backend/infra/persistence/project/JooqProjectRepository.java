package com.packing.backend.infra.persistence.project;

import com.packing.backend.core.project.port.out.ProjectRepository;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectMember;
import com.packing.backend.infra.persistence.shared.AggregateWriter;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.ProjectMembers.PROJECT_MEMBERS;
import static com.packing.backend.infra.persistence.jooq.tables.Projects.PROJECTS;
import static com.packing.backend.infra.persistence.project.ProjectQueries.MEMBERS;

@Repository
@RequiredArgsConstructor
public class JooqProjectRepository implements ProjectRepository {

    private final DSLContext      dsl;
    private final AggregateWriter writer;

    @Override
    public Project save(Project project) {
        writer.upsert(ProjectRecordMapper.TABLE,
                      ProjectRecordMapper.toRecord(project),
                      project.version());
        replaceMembers(project);
        project.markPersisted();
        return project;
    }

    @Override
    public Optional<Project> findById(ProjectId id) {
        return dsl.select(PROJECTS.asterisk(), MEMBERS)
                  .from(PROJECTS)
                  .where(PROJECTS.ID.eq(id))
                  .fetchOptional()
                  .map(JooqProjectRepository::toProject);
    }

    private static Project toProject(Record row) {
        return ProjectRecordMapper.toDomain(row.into(PROJECTS), row.get(MEMBERS));
    }

    private void replaceMembers(Project project) {
        dsl.deleteFrom(PROJECT_MEMBERS)
           .where(PROJECT_MEMBERS.PROJECT_ID.eq(project.id()))
           .execute();

        List<ProjectMember> members = project.members();
        if (members.isEmpty()) {
            return;
        }

        List<Query> inserts = new ArrayList<>(members.size());
        for (ProjectMember member : members) {
            inserts.add(dsl.insertInto(PROJECT_MEMBERS)
                           .set(ProjectRecordMapper.toRecord(project.id(), member)));
        }
        dsl.batch(inserts)
           .execute();
    }

}
