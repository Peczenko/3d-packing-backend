package com.packing.backend.infra.persistence.file;

import com.packing.backend.core.file.FileView;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StoredFile;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@JooqTest
@Import(TestcontainersConfiguration.class)
class JooqFileFinderIT {

    private static final Checksum CHECKSUM =
            Checksum.ofHex("d3a15aa3cd30cc79123d6a50d2809ed794a452e67fa857bbc7ac343cbfca9971");

    @Autowired
    private DSLContext dsl;

    private UserId owner;
    private ProjectId project;
    private ProjectId otherProject;

    private JooqFileFinder finder() {
        return new JooqFileFinder(dsl);
    }

    private JooqFileRepository repository() {
        return new JooqFileRepository(dsl, new AggregateWriter(dsl));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @BeforeEach
    void createOwnerAndProjects() {
        User user = User.register(new FirebaseUid("uid-owner"), new Email("owner@example.com"),
                new Username("owner"), "Owner", now());
        new JooqUserRepository(dsl, new AggregateWriter(dsl)).save(user);
        owner = user.id();

        project = persistProject("Chassis");
        otherProject = persistProject("Other");
    }

    private ProjectId persistProject(String name) {
        Project owned = Project.create(new ProjectName(name), owner, now());
        new JooqProjectRepository(dsl, new AggregateWriter(dsl)).save(owned);
        return owned.id();
    }

    private StoredFile persistFile(ProjectId projectId, String filename, Instant createdAt) {
        StoredFile file = StoredFile.upload(FileId.generate(), owner, projectId,
                new FileName(filename), 2_048L, CHECKSUM, createdAt);
        repository().save(file);
        return file;
    }

    @Test
    void listsNewestFirstAndExcludesTombstones() {
        Instant base = now();
        persistFile(project, "older.stl", base);
        persistFile(project, "newer.stl", base.plusSeconds(1));
        StoredFile removed = persistFile(project, "removed.stl", base.plusSeconds(2));
        removed.delete(now());
        repository().save(removed);

        Page<FileView> page = finder().listAvailableInProject(project, new PageRequest(0, 10));

        assertThat(page.content()).extracting(FileView::filename)
                .containsExactly("newer.stl", "older.stl");
        assertThat(page.totalElements()).isEqualTo(2L);
    }

    @Test
    void pagesAndReportsTheTotalIndependentlyOfThePageSize() {
        Instant base = now();
        for (int i = 0; i < 5; i++) {
            persistFile(project, "part" + i + ".stl", base.plusSeconds(i));
        }

        assertThat(finder().listAvailableInProject(project, new PageRequest(0, 2)).content())
                .hasSize(2);
        assertThat(finder().listAvailableInProject(project, new PageRequest(2, 2)).content())
                .hasSize(1);
        assertThat(finder().listAvailableInProject(project, new PageRequest(5, 2)).content())
                .isEmpty();
        assertThat(finder().listAvailableInProject(project, new PageRequest(5, 2)).totalElements())
                .isEqualTo(5L);
    }

    @Test
    void listingIsScopedToTheProjectNotTheUploader() {
        persistFile(project, "bracket.stl", now());

        Page<FileView> page = finder().listAvailableInProject(otherProject, new PageRequest(0, 10));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    /** FileView omits storageKey on purpose: exposing it would leak the object layout. */
    @Test
    void viewCarriesTheFieldsAClientRenders() {
        StoredFile file = persistFile(project, "bracket.stl", now());

        assertThat(finder().listAvailableInProject(project, new PageRequest(0, 10)).content())
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.id()).isEqualTo(file.id().value());
                    assertThat(view.projectId()).isEqualTo(project.value());
                    assertThat(view.filename()).isEqualTo("bracket.stl");
                    assertThat(view.format()).isEqualTo(ModelFormat.STL);
                    assertThat(view.sizeBytes()).isEqualTo(2_048L);
                    assertThat(view.checksumSha256()).isEqualTo(CHECKSUM.value());
                });
    }
}
