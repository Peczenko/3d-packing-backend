package com.packing.backend.infra.persistence.packing;

import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;
import com.packing.backend.infra.TestcontainersConfiguration;
import com.packing.backend.infra.persistence.project.JooqProjectRepository;
import com.packing.backend.infra.persistence.shared.AggregateWriter;
import com.packing.backend.infra.persistence.user.JooqUserRepository;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.packing.backend.infra.persistence.jooq.tables.PackingJobs.PACKING_JOBS;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JooqTest
@Import(TestcontainersConfiguration.class)
class PackingJobInputShapeIT {

    private static final String ITEMS = "[{\"fileId\":\"0b6c3c1e-4c4f-4a39-9b5e-2b8c2e6b1f00\",\"copies\":1}]";

    @Autowired
    private DSLContext dsl;

    private UserId    user;
    private ProjectId project;

    @BeforeEach
    void createUserAndProject() {
        Instant now = now();
        User owner = User.register(new FirebaseUid("shape-owner"),
                                   new Email("shape-owner@example.com"),
                                   new Username("shape-owner"),
                                   "Shape Owner",
                                   now);
        new JooqUserRepository(dsl, new AggregateWriter(dsl)).save(owner);
        user = owner.id();
        Project owned = Project.create(new ProjectName("Shape Project"), user, now);
        new JooqProjectRepository(dsl, new AggregateWriter(dsl)).save(owned);
        project = owned.id();
    }

    @Test
    void acceptsALegacyRow() {
        assertThatCode(() -> insert("{\"testField\":42}", null, null, null)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAStructuredRow() {
        assertThatCode(() -> insert(null, 1, ITEMS, "{}")).doesNotThrowAnyException();
    }

    @Test
    void rejectsARowCarryingBothShapes() {
        assertThatThrownBy(() -> insert("{}", 1, ITEMS, "{}")).hasStackTraceContaining("ck_packing_jobs_input_shape");
    }

    @Test
    void rejectsARowCarryingNeitherShape() {
        assertThatThrownBy(() -> insert(null, null, null, null)).hasStackTraceContaining("ck_packing_jobs_input_shape");
    }

    @Test
    void rejectsAStructuredRowWithoutParameters() {
        assertThatThrownBy(() -> insert(null, 1, ITEMS, null)).hasStackTraceContaining("ck_packing_jobs_input_shape");
    }

    @Test
    void rejectsItemsThatAreNotAnArray() {
        assertThatThrownBy(() -> insert(null, 1, "{}", "{}")).hasStackTraceContaining("ck_packing_jobs_input_shape");
    }

    @Test
    void rejectsParametersThatAreNotAnObject() {
        assertThatThrownBy(() -> insert(null, 1, ITEMS, "[]")).hasStackTraceContaining("ck_packing_jobs_input_shape");
    }

    @Test
    void rejectsASchemaVersionBelowOne() {
        assertThatThrownBy(() -> insert(null, 0, ITEMS, "{}")).hasStackTraceContaining("ck_packing_jobs_input_shape");
    }

    private void insert(String specJson, Integer schemaVersion, String items, String parameters) {
        JSONB spec = specJson == null ? null : JSONB.valueOf(specJson);
        JSONB itemList = items == null ? null : JSONB.valueOf(items);
        JSONB parameterObject = parameters == null ? null : JSONB.valueOf(parameters);
        dsl.insertInto(PACKING_JOBS)
           .set(PACKING_JOBS.ID, PackingJobId.generate())
           .set(PACKING_JOBS.PROJECT_ID, project)
           .set(PACKING_JOBS.CREATED_BY, user)
           .set(PACKING_JOBS.SPEC_JSON, spec)
           .set(PACKING_JOBS.INPUT_SCHEMA_VERSION, schemaVersion)
           .set(PACKING_JOBS.ITEMS, itemList)
           .set(PACKING_JOBS.PARAMETERS, parameterObject)
           .set(PACKING_JOBS.MAX_RUNTIME_SECONDS, 60L)
           .set(PACKING_JOBS.STATUS, PackingJobStatus.QUEUED)
           .set(PACKING_JOBS.VERSION, 0L)
           .set(PACKING_JOBS.CREATED_AT, now())
           .execute();
    }

    private static Instant now() {
        return Instant.now()
                      .truncatedTo(ChronoUnit.MICROS);
    }
}
