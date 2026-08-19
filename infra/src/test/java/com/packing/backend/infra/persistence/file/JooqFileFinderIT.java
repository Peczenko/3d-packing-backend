package com.packing.backend.infra.persistence.file;

import com.packing.backend.core.file.FileView;
import com.packing.backend.core.file.FileListCriteria;
import com.packing.backend.core.shared.InstantRange;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JooqTest
@Import(TestcontainersConfiguration.class)
class JooqFileFinderIT {

    private static final Checksum CHECKSUM = Checksum.ofHex("d3a15aa3cd30cc79123d6a50d2809ed794a452e67fa857bbc7ac343cbfca9971");

    @Autowired
    private DSLContext dsl;

    private UserId    owner;
    private ProjectId project;
    private ProjectId otherProject;

    private JooqFileFinder finder() {
        return new JooqFileFinder(dsl);
    }

    private JooqFileRepository repository() {
        return new JooqFileRepository(dsl, new AggregateWriter(dsl));
    }

    private static Instant now() {
        return Instant.now()
                      .truncatedTo(ChronoUnit.MICROS);
    }

    @BeforeEach
    void createOwnerAndProjects() {
        User user = User.register(new FirebaseUid("uid-owner"),
                                  new Email("owner@example.com"),
                                  new Username("owner"),
                                  "Owner",
                                  now());
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
        return persistFile(projectId, owner, filename, createdAt);
    }

    private StoredFile persistFile(ProjectId projectId, UserId uploader, String filename,
                                   Instant createdAt) {
        return persistFile(projectId, uploader, filename, 2_048L, createdAt);
    }

    private StoredFile persistFile(ProjectId projectId, UserId uploader, String filename,
                                   long sizeBytes, Instant createdAt) {
        StoredFile file = StoredFile.upload(FileId.generate(),
                                            uploader,
                                            projectId,
                                            new FileName(filename),
                                            sizeBytes,
                                            CHECKSUM,
                                            createdAt);
        repository().save(file);
        return file;
    }

    private static FileListCriteria criteria(PageRequest page) {
        return new FileListCriteria(page,
                                    null,
                                    Set.of(),
                                    new InstantRange(null, null),
                                    FileListCriteria.SortField.CREATED_AT,
                                    SortDirection.DESC);
    }

    private static FileListCriteria criteria(PageRequest page, String search,
                                             Set<ModelFormat> formats, InstantRange createdAt,
                                             FileListCriteria.SortField sort, SortDirection direction) {
        return new FileListCriteria(page, search, formats, createdAt, sort, direction);
    }

    @Test
    void listsNewestFirstAndExcludesTombstones() {
        Instant base = now();
        persistFile(project, "older.stl", base);
        persistFile(project, "newer.stl", base.plusSeconds(1));
        StoredFile removed = persistFile(project, "removed.stl", base.plusSeconds(2));
        removed.delete(now());
        repository().save(removed);

        Page<FileView> page = finder().listAvailableInProject(project, criteria(new PageRequest(0, 10)));

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
        persistFile(project, "ignore.obj", base.plusSeconds(6));

        FileListCriteria filtered = criteria(new PageRequest(0, 2),
                                             "part",
                                             Set.of(),
                                             new InstantRange(null, null),
                                             FileListCriteria.SortField.CREATED_AT,
                                             SortDirection.DESC);
        assertThat(finder().listAvailableInProject(project, filtered)
                           .content())
                                      .hasSize(2);
        assertThat(finder().listAvailableInProject(project,
                                                   new FileListCriteria(
                                                                        new PageRequest(2, 2),
                                                                        filtered.search(),
                                                                        filtered.formats(),
                                                                        filtered.createdAt(),
                                                                        filtered.sort(),
                                                                        filtered.direction()))
                           .content())
                                      .hasSize(1);
        assertThat(finder().listAvailableInProject(project,
                                                   new FileListCriteria(
                                                                        new PageRequest(5, 2),
                                                                        filtered.search(),
                                                                        filtered.formats(),
                                                                        filtered.createdAt(),
                                                                        filtered.sort(),
                                                                        filtered.direction()))
                           .content())
                                      .isEmpty();
        assertThat(finder().listAvailableInProject(project,
                                                   new FileListCriteria(
                                                                        new PageRequest(5, 2),
                                                                        filtered.search(),
                                                                        filtered.formats(),
                                                                        filtered.createdAt(),
                                                                        filtered.sort(),
                                                                        filtered.direction()))
                           .totalElements())
                                            .isEqualTo(5L);
    }

    @Test
    void listingIsScopedToTheProjectNotTheUploader() {
        persistFile(project, "bracket.stl", now());

        Page<FileView> page = finder().listAvailableInProject(otherProject, criteria(new PageRequest(0, 10)));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void listingIncludesFilesUploadedByAnyMemberNotJustTheOwner() {
        User other = User.register(new FirebaseUid("uid-other"),
                                   new Email("other@example.com"),
                                   new Username("other"),
                                   "Other",
                                   now());
        new JooqUserRepository(dsl, new AggregateWriter(dsl)).save(other);

        Instant base = now();
        persistFile(project, owner, "mine.stl", base);
        persistFile(project, other.id(), "theirs.stl", base.plusSeconds(1));

        Page<FileView> page = finder().listAvailableInProject(project, criteria(new PageRequest(0, 10)));

        assertThat(page.content()).extracting(FileView::filename)
                                  .containsExactly("theirs.stl", "mine.stl");
    }

    @Test
    void viewCarriesTheFieldsAClientRenders() {
        StoredFile file = persistFile(project, "bracket.stl", now());

        assertThat(finder().listAvailableInProject(project, criteria(new PageRequest(0, 10)))
                           .content())
                                      .singleElement()
                                      .satisfies(view -> {
                                          assertThat(view.id()).isEqualTo(file.id()
                                                                              .value());
                                          assertThat(view.projectId()).isEqualTo(project.value());
                                          assertThat(view.filename()).isEqualTo("bracket.stl");
                                          assertThat(view.format()).isEqualTo(ModelFormat.STL);
                                          assertThat(view.sizeBytes()).isEqualTo(2_048L);
                                          assertThat(view.checksumSha256()).isEqualTo(CHECKSUM.value());
                                      });
    }

    @Test
    void treatsPercentAndUnderscoreAsLiteralCaseInsensitiveFilenameCharacters() {
        Instant base = now();
        persistFile(project, "percent%under_score.stl", base);
        persistFile(project, "percentXunder_score.stl", base.plusSeconds(1));
        persistFile(project, "percent%underXscore.stl", base.plusSeconds(2));

        Page<FileView> page = finder().listAvailableInProject(
                                                              project,
                                                              criteria(new PageRequest(0, 10),
                                                                       "PERCENT%UNDER_",
                                                                       Set.of(ModelFormat.STL),
                                                                       new InstantRange(null, null),
                                                                       FileListCriteria.SortField.CREATED_AT,
                                                                       SortDirection.ASC));

        assertThat(page.content()).extracting(FileView::filename)
                                  .containsExactly("percent%under_score.stl");
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    void excludesAnOtherwiseMatchingFileAtTheExclusiveCreatedBeforeBoundary() {
        Instant base = now();
        persistFile(project, "bracket-in-range.stl", base);
        persistFile(project, "bracket-at-before.stl", base.plusSeconds(1));

        Page<FileView> page = finder().listAvailableInProject(
                                                              project,
                                                              criteria(new PageRequest(0, 10),
                                                                       "bracket",
                                                                       Set.of(ModelFormat.STL),
                                                                       new InstantRange(base, base.plusSeconds(1)),
                                                                       FileListCriteria.SortField.CREATED_AT,
                                                                       SortDirection.ASC));

        assertThat(page.content()).extracting(FileView::filename)
                                  .containsExactly("bracket-in-range.stl");
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    void sortsByEverySupportedFieldInBothDirections() {
        Instant base = now();
        persistFile(project, owner, "charlie.glb", 3_000L, base.plusSeconds(2));
        persistFile(project, owner, "Bravo.obj", 2_000L, base.plusSeconds(1));
        persistFile(project, owner, "alpha.stl", 1_000L, base);

        assertSorted(FileListCriteria.SortField.FILENAME,
                     List.of("alpha.stl", "Bravo.obj", "charlie.glb"));
        assertSorted(FileListCriteria.SortField.FORMAT,
                     List.of("charlie.glb", "Bravo.obj", "alpha.stl"));
        assertSorted(FileListCriteria.SortField.SIZE_BYTES,
                     List.of("alpha.stl", "Bravo.obj", "charlie.glb"));
        assertSorted(FileListCriteria.SortField.CREATED_AT,
                     List.of("alpha.stl", "Bravo.obj", "charlie.glb"));
    }

    @Test
    void breaksCaseInsensitiveFilenameTiesByIdInTheRequestedDirection() {
        Instant createdAt = now();
        StoredFile first = persistFile(project, "tie.stl", createdAt);
        StoredFile second = persistFile(project, "Tie.stl", createdAt);
        List<UUID> ascending = List.of(first.id()
                                            .value(),
                                       second.id()
                                             .value())
                                   .stream()
                                   .sorted(Comparator.comparing(UUID::toString))
                                   .toList();

        Page<FileView> asc = finder().listAvailableInProject(project,
                                                             criteria(new PageRequest(0, 10),
                                                                      null,
                                                                      Set.of(),
                                                                      new InstantRange(null, null),
                                                                      FileListCriteria.SortField.FILENAME,
                                                                      SortDirection.ASC));
        Page<FileView> desc = finder().listAvailableInProject(project,
                                                              criteria(new PageRequest(0, 10),
                                                                       null,
                                                                       Set.of(),
                                                                       new InstantRange(null, null),
                                                                       FileListCriteria.SortField.FILENAME,
                                                                       SortDirection.DESC));

        assertThat(asc.content()).extracting(FileView::id)
                                 .containsExactlyElementsOf(ascending);
        assertThat(desc.content()).extracting(FileView::id)
                                  .containsExactlyElementsOf(ascending.reversed());
    }

    private void assertSorted(FileListCriteria.SortField sort, List<String> ascending) {
        Page<FileView> asc = finder().listAvailableInProject(project,
                                                             criteria(new PageRequest(0, 10),
                                                                      null,
                                                                      Set.of(),
                                                                      new InstantRange(null, null),
                                                                      sort,
                                                                      SortDirection.ASC));
        Page<FileView> desc = finder().listAvailableInProject(project,
                                                              criteria(new PageRequest(0, 10),
                                                                       null,
                                                                       Set.of(),
                                                                       new InstantRange(null, null),
                                                                       sort,
                                                                       SortDirection.DESC));

        assertThat(asc.content()).extracting(FileView::filename)
                                 .containsExactlyElementsOf(ascending);
        assertThat(desc.content()).extracting(FileView::filename)
                                  .containsExactlyElementsOf(ascending.reversed());
    }
}
