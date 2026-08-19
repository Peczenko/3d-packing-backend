package com.packing.backend.infra.persistence.shared;

import com.packing.backend.infra.InfraTestApplication;
import com.packing.backend.infra.TestcontainersConfiguration;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

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
}
