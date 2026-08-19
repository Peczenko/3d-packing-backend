package com.packing.backend.infra.persistence.shared;

import com.packing.backend.core.file.FileListCriteria;
import com.packing.backend.core.project.ProjectListCriteria;
import com.packing.backend.core.project.ProjectMemberListCriteria;
import com.packing.backend.core.shared.InstantRange;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.infra.InfraTestApplication;
import com.packing.backend.infra.TestcontainersConfiguration;
import com.packing.backend.infra.persistence.file.JooqFileFinder;
import com.packing.backend.infra.persistence.project.JooqProjectFinder;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListenerProvider;
import org.jooq.impl.DefaultExecuteListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = InfraTestApplication.class, properties = {
        "app.storage.enabled=false",
        "firebase.admin-enabled=false",
        "spring.main.lazy-initialization=true"
})
@Import(TestcontainersConfiguration.class)
class CollectionQueryIndexesIT {

    @Autowired
    private DSLContext dsl;

    @Test
    void createsTheExtensionAndIndexesUsedByCollectionQueries() {
        assertThat(dsl.fetchValue(
                                  "select count(*)::integer from pg_extension where extname = 'pg_trgm'",
                                  Integer.class)).isEqualTo(1);

        assertThat(dsl.fetch(
                             "select indexname from pg_indexes where schemaname = 'public'")
                      .getValues("indexname", String.class))
                                                            .contains(
                                                                      "ix_projects_name_trgm",
                                                                      "ix_files_filename_trgm",
                                                                      "ix_packing_jobs_search_trgm",
                                                                      "ix_users_username_trgm",
                                                                      "ix_users_display_name_trgm",
                                                                      "ix_project_members_project_added",
                                                                      "ix_packing_jobs_project_started",
                                                                      "ix_packing_jobs_project_finished");
    }

    @Test
    void searchQueriesMatchTheLowerExpressionTrigramIndexes() {
        List<String> executedSql = new ArrayList<>();
        List<Object> bindValues = new ArrayList<>();
        DSLContext capturingDsl = dsl.configuration()
                                     .derive((ExecuteListenerProvider) () -> new DefaultExecuteListener() {

                                         @Override
                                         public void executeStart(ExecuteContext ctx) {
                                             executedSql.add(ctx.sql());
                                             bindValues.addAll(ctx.query()
                                                                  .getBindValues());
                                         }
                                     })
                                     .dsl();
        JooqProjectFinder projectFinder = new JooqProjectFinder(capturingDsl);

        projectFinder.listForMember(UserId.generate(), projectCriteria("MiXeD%_CaSe"));
        projectFinder.listMembersFor(UserId.generate(), ProjectId.generate(), memberCriteria("MiXeD%_CaSe"));
        new JooqFileFinder(capturingDsl).listAvailableInProject(ProjectId.generate(), fileCriteria("MiXeD%_CaSe"));

        assertThat(String.join("\n", executedSql))
                                                  .contains("lower(\"projects\".\"name\") like",
                                                            "lower(\"users\".\"username\") like",
                                                            "lower(\"users\".\"display_name\") like",
                                                            "lower(\"files\".\"original_filename\") like")
                                                  .contains("escape '!'")
                                                  .doesNotContain(" ilike ");
        assertThat(bindValues).contains("mixed%_case")
                              .doesNotContain("MiXeD%_CaSe");
    }

    private static ProjectListCriteria projectCriteria(String search) {
        return new ProjectListCriteria(new PageRequest(0, 20),
                                       search,
                                       Set.of(),
                                       Set.of(),
                                       new InstantRange(null, null),
                                       new InstantRange(null, null),
                                       ProjectListCriteria.SortField.CREATED_AT,
                                       SortDirection.DESC);
    }

    private static ProjectMemberListCriteria memberCriteria(String search) {
        return new ProjectMemberListCriteria(new PageRequest(0, 20),
                                             search,
                                             Set.of(),
                                             new InstantRange(null, null),
                                             ProjectMemberListCriteria.SortField.ADDED_AT,
                                             SortDirection.ASC);
    }

    private static FileListCriteria fileCriteria(String search) {
        return new FileListCriteria(new PageRequest(0, 20),
                                    search,
                                    Set.of(),
                                    new InstantRange(null, null),
                                    FileListCriteria.SortField.CREATED_AT,
                                    SortDirection.DESC);
    }
}
