package com.packing.backend.core.packing;

import com.packing.backend.core.packing.PackingJobApplicationService.CancelPackingJobCommand;
import com.packing.backend.core.packing.PackingJobApplicationService.CreatePackingJobCommand;
import com.packing.backend.core.packing.PackingJobApplicationService.DraftQuery;
import com.packing.backend.core.packing.PackingJobApplicationService.DraftSource;
import com.packing.backend.core.packing.PackingJobApplicationService.ListPackingJobsQuery;
import com.packing.backend.core.packing.PackingJobApplicationService.OmittedItem;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobDraft;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobPreviewQuery;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobQuery;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobResultBinQuery;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobResultQuery;
import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.packing.port.out.PackingInputSchema;
import com.packing.backend.core.packing.port.out.PackingInputSchema.Reconciled;
import com.packing.backend.core.packing.port.out.PackingInputSchema.RequestedItem;
import com.packing.backend.core.packing.port.out.PackingInputSchema.SchemaDocument;
import com.packing.backend.core.packing.port.out.PackingInputSchema.Settings;
import com.packing.backend.core.packing.port.out.PackingInputSchema.ValidatedInput;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.core.project.port.out.ProjectAccessLookup;
import com.packing.backend.core.project.port.out.ProjectAccessLookup.ProjectAccess;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.InstantRange;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.packing.PackingInput;
import com.packing.backend.domain.packing.PackingInputs;
import com.packing.backend.domain.packing.PackingItem;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.packing.PackingParameters;
import com.packing.backend.domain.packing.PackingPreview;
import com.packing.backend.domain.packing.PreviewModel;
import com.packing.backend.domain.packing.PreviewQuality;
import com.packing.backend.domain.packing.ResultBin;
import com.packing.backend.domain.packing.event.PackingJobCancelled;
import com.packing.backend.domain.packing.event.PackingJobQueued;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectNotFoundException;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.shared.PermissionDeniedException;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.shared.ResourceNotFoundException;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingJobApplicationServiceTest {

    private static final Instant                NOW              = Instant.parse("2026-08-01T10:15:30Z");
    private static final String                 FIREBASE_UID     = "firebase-1";
    private static final ProjectId              PROJECT_ID       = ProjectId.generate();
    private static final ProjectId              OTHER_PROJECT_ID = ProjectId.generate();
    private static final UserId                 USER_ID          = UserId.generate();
    private static final PageRequest            PAGE             = new PageRequest(0, 20);
    private static final String                 BODY             = "{\"items\":[]}";
    private static final PackingParameters      PARAMETERS       = new PackingParameters("{\"seed\":7}");
    private static final PackingJobListCriteria CRITERIA         = new PackingJobListCriteria(
                                                                                              PAGE,
                                                                                              "engine",
                                                                                              Set.of(PackingJobStatus.RUNNING),
                                                                                              new InstantRange(NOW.minusSeconds(60), NOW.plusSeconds(60)),
                                                                                              new InstantRange(null, null),
                                                                                              new InstantRange(null, null),
                                                                                              PackingJobListCriteria.SortField.CREATED_AT,
                                                                                              SortDirection.DESC);

    private static final SchemaDocument SCHEMA_V1 = new SchemaDocument(1, "{}", "e".repeat(64));

    @Mock
    private PackingJobRepository    repository;
    @Mock
    private PackingJobFinder        finder;
    @Mock
    private ProjectAccessLookup     access;
    @Mock
    private DomainEventPublisher    events;
    @Mock
    private PackingJobArtifactStore artifacts;
    @Mock
    private PackingInputSchema      schema;
    @Mock
    private FileRepository          files;

    private PackingJobApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PackingJobApplicationService(repository,
                                                   finder,
                                                   access,
                                                   events,
                                                   artifacts,
                                                   schema,
                                                   files,
                                                   Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createQueuesTheValidatedInputWithASnapshotOfEachProjectFile() {
        StoredFile bunny = file(PROJECT_ID, "bunny.obj");
        PackingJobView expected = view(UUID.randomUUID(), PROJECT_ID.value());
        givenAccess(ProjectPermission.WRITE);
        when(schema.validate(BODY)).thenReturn(validated(new RequestedItem(bunny.id(), 3)));
        when(schema.acceptedFormats()).thenReturn(Set.of(ModelFormat.OBJ));
        when(files.findById(bunny.id())).thenReturn(Optional.of(bunny));
        when(finder.detailInProject(eq(PROJECT_ID), any())).thenReturn(Optional.of(expected));

        PackingJobView result = service.create(create());

        ArgumentCaptor<PackingJob> saved = ArgumentCaptor.forClass(PackingJob.class);
        InOrder order = inOrder(repository, events);
        order.verify(repository)
             .save(saved.capture());
        order.verify(events)
             .publishAll(argThat(published -> published.size() == 1 && published.iterator()
                                                                                .next() instanceof PackingJobQueued));
        assertThat(saved.getValue()
                        .input()).isEqualTo(new PackingInput(1, List.of(PackingItem.of(bunny, 3)), PARAMETERS));
        assertThat(saved.getValue()
                        .maxRuntimeSeconds()).isEqualTo(120);
        assertThat(saved.getValue()
                        .requestedBy()).isEqualTo(USER_ID);
        assertThat(saved.getValue()
                        .createdAt()).isEqualTo(NOW);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void createReportsEveryUnusableFileAtItsPointer() {
        FileId missing = FileId.generate();
        StoredFile foreign = file(OTHER_PROJECT_ID, "foreign.obj");
        StoredFile deleted = file(PROJECT_ID, "deleted.obj");
        deleted.delete(NOW);
        StoredFile bracket = file(PROJECT_ID, "bracket.stl");
        givenAccess(ProjectPermission.WRITE);
        when(schema.validate(BODY)).thenReturn(validated(new RequestedItem(missing, 1),
                                                         new RequestedItem(foreign.id(), 1),
                                                         new RequestedItem(deleted.id(), 1),
                                                         new RequestedItem(bracket.id(), 1)));
        when(schema.acceptedFormats()).thenReturn(Set.of(ModelFormat.OBJ));
        when(files.findById(missing)).thenReturn(Optional.empty());
        when(files.findById(foreign.id())).thenReturn(Optional.of(foreign));
        when(files.findById(deleted.id())).thenReturn(Optional.of(deleted));
        when(files.findById(bracket.id())).thenReturn(Optional.of(bracket));

        assertThatThrownBy(() -> service.create(create()))
                                                          .isInstanceOfSatisfying(PackingInputRejectedException.class,
                                                                                  e -> assertThat(e.errors()).containsExactly(
                                                                                                                              entry("/items/0/fileId",
                                                                                                                                    "File is not available in this project"),
                                                                                                                              entry("/items/1/fileId",
                                                                                                                                    "File is not available in this project"),
                                                                                                                              entry("/items/2/fileId",
                                                                                                                                    "File is not available in this project"),
                                                                                                                              entry("/items/3/fileId",
                                                                                                                                    "bracket.stl is STL; the packing engine accepts OBJ")));
        verify(repository, never()).save(any());
        verify(events, never()).publishAll(any());
    }

    @Test
    void createChecksPermissionBeforeLookingAtTheInput() {
        givenAccess(ProjectPermission.READ);

        assertThatThrownBy(() -> service.create(create())).isInstanceOf(PermissionDeniedException.class);

        verify(schema, never()).validate(any());
        verify(repository, never()).save(any());
    }

    @Test
    void createIsRefusedWhileTheProjectIsDisabled() {
        when(access.findAccess(new FirebaseUid(FIREBASE_UID), PROJECT_ID)).thenReturn(Optional.of(
                                                                                                  new ProjectAccess(USER_ID,
                                                                                                                    PROJECT_ID,
                                                                                                                    ProjectStatus.DISABLED,
                                                                                                                    ProjectPermission.WRITE)));

        assertThatThrownBy(() -> service.create(create())).isInstanceOf(ResourceConflictException.class);

        verify(schema, never()).validate(any());
        verify(repository, never()).save(any());
    }

    @Test
    void createPassesASchemaRejectionThroughUntouched() {
        PackingInputInvalidException rejection = new PackingInputInvalidException(Map.of("/items", "is required"));
        givenAccess(ProjectPermission.WRITE);
        when(schema.validate(BODY)).thenThrow(rejection);

        assertThatThrownBy(() -> service.create(create())).isSameAs(rejection);

        verifyNoInteractions(files);
        verify(repository, never()).save(any());
    }

    @Test
    void listUsesTheAuthorizedProjectIdAndPassesCriteriaUnchangedToTheFinder() {
        Page<PackingJobView> expected = new Page<>(List.of(view(UUID.randomUUID(), PROJECT_ID.value())),
                                                   PAGE.page(),
                                                   PAGE.size(),
                                                   1);
        when(access.findAccess(new FirebaseUid(FIREBASE_UID), OTHER_PROJECT_ID)).thenReturn(
                                                                                            Optional.of(projectAccess(ProjectPermission.READ)));
        when(finder.listInProject(PROJECT_ID, CRITERIA)).thenReturn(expected);

        Page<PackingJobView> result = service.list(new ListPackingJobsQuery(
                                                                            FIREBASE_UID,
                                                                            OTHER_PROJECT_ID.value(),
                                                                            CRITERIA));

        assertThat(result).isEqualTo(expected);
        verify(finder).listInProject(PROJECT_ID, CRITERIA);
        verify(finder, never()).listInProject(OTHER_PROJECT_ID, CRITERIA);
    }

    @Test
    void getRequiresReadPermissionAndUsesBothProjectAndJobIds() {
        UUID jobId = UUID.randomUUID();
        PackingJobView expected = view(jobId, PROJECT_ID.value());
        givenReadAccess();
        when(finder.detailInProject(PROJECT_ID, new com.packing.backend.domain.packing.PackingJobId(jobId)))
                                                                                                            .thenReturn(Optional.of(expected));

        PackingJobView result = service.get(new PackingJobQuery(FIREBASE_UID, PROJECT_ID.value(), jobId));

        assertThat(result).isEqualTo(expected);
        verify(finder).detailInProject(PROJECT_ID,
                                       new com.packing.backend.domain.packing.PackingJobId(jobId));
    }

    @Test
    void getMapsAMissingJobInTheProjectToPackingJobNotFound() {
        UUID jobId = UUID.randomUUID();
        givenReadAccess();
        when(finder.detailInProject(PROJECT_ID, new com.packing.backend.domain.packing.PackingJobId(jobId)))
                                                                                                            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(new PackingJobQuery(FIREBASE_UID,
                                                                 PROJECT_ID.value(),
                                                                 jobId)))
                                                                         .isInstanceOf(PackingJobNotFoundException.class);
    }

    @Test
    void prepareResultDownloadAllowsReadAccessAndIssuesATenMinuteUrlForASucceededJob() {
        UUID jobId = UUID.randomUUID();
        PackingJob job = job(jobId, PROJECT_ID, PackingJobStatus.SUCCEEDED);
        PackingJobArtifactStore.TemporaryUrl expected = new PackingJobArtifactStore.TemporaryUrl(
                                                                                                 URI.create("https://storage.example/result?sig=secret"),
                                                                                                 NOW.plus(Duration.ofMinutes(10)));
        givenReadAccess();
        when(repository.findById(new com.packing.backend.domain.packing.PackingJobId(jobId)))
                                                                                             .thenReturn(Optional.of(job));
        when(artifacts.findResult(job.id())).thenReturn(Optional.of(resultArtifact()));
        when(artifacts.createResultDownloadUrl(job.id(), Duration.ofMinutes(10))).thenReturn(expected);

        PackingJobArtifactStore.TemporaryUrl result = service.prepareResultDownload(
                                                                                    new PackingJobResultQuery(FIREBASE_UID, PROJECT_ID.value(), jobId));

        assertThat(result).isEqualTo(expected);
        verify(artifacts).createResultDownloadUrl(job.id(), Duration.ofMinutes(10));
    }

    @Test
    void prepareResultBinDownloadNamesTheContainerAndSaysHowManyThereAre() {
        UUID jobId = UUID.randomUUID();
        PackingJob job = job(jobId, PROJECT_ID, PackingJobStatus.SUCCEEDED, PackingPreview.NONE, TWO_BINS);
        PackingJobArtifactStore.TemporaryUrl expected = new PackingJobArtifactStore.TemporaryUrl(
                                                                                                 URI.create("https://storage.example/bin?sig=secret"),
                                                                                                 NOW.plus(Duration.ofMinutes(10)));
        givenReadAccess();
        when(repository.findById(job.id())).thenReturn(Optional.of(job));
        when(artifacts.createResultBinUrl(job.id(),
                                          "b".repeat(64),
                                          2,
                                          2,
                                          Duration.ofMinutes(10),
                                          "packing-job-" + jobId + "-bin-002.obj")).thenReturn(expected);

        PackingJobArtifactStore.TemporaryUrl result = service.prepareResultBinDownload(
                                                                                       new PackingJobResultBinQuery(FIREBASE_UID, PROJECT_ID.value(), jobId, 2));

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void prepareResultBinDownloadRejectsAnUnfinishedJobAndAContainerThatWasNeverFilled() {
        UUID queuedId = UUID.randomUUID();
        UUID succeededId = UUID.randomUUID();
        givenReadAccess();
        when(repository.findById(new com.packing.backend.domain.packing.PackingJobId(queuedId)))
                                                                                                .thenReturn(Optional.of(job(queuedId, PROJECT_ID, PackingJobStatus.QUEUED)));
        when(repository.findById(new com.packing.backend.domain.packing.PackingJobId(succeededId)))
                                                                                                   .thenReturn(Optional.of(job(succeededId,
                                                                                                                               PROJECT_ID,
                                                                                                                               PackingJobStatus.SUCCEEDED,
                                                                                                                               PackingPreview.NONE,
                                                                                                                               TWO_BINS)));

        assertThatThrownBy(() -> service.prepareResultBinDownload(new PackingJobResultBinQuery(FIREBASE_UID, PROJECT_ID.value(), queuedId, 1)))
                                                                                                                                               .isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(() -> service.prepareResultBinDownload(new PackingJobResultBinQuery(FIREBASE_UID, PROJECT_ID.value(), succeededId, 3)))
                                                                                                                                                  .isInstanceOf(ResourceNotFoundException.class);
        verify(artifacts, never()).createResultBinUrl(any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void prepareResultBinDownloadRejectsANonMemberBeforeLoadingTheJob() {
        when(access.findAccess(new FirebaseUid(FIREBASE_UID), PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepareResultBinDownload(new PackingJobResultBinQuery(FIREBASE_UID, PROJECT_ID.value(), UUID.randomUUID(), 1)))
                                                                                                                                                        .isInstanceOf(ProjectNotFoundException.class);

        verify(repository, never()).findById(any());
    }

    @Test
    void preparePreviewDownloadIssuesAnInlineUrlForAReadyQuality() {
        UUID jobId = UUID.randomUUID();
        PackingJob job = job(jobId, PROJECT_ID, PackingJobStatus.SUCCEEDED, readyPreview());
        PackingJobArtifactStore.TemporaryUrl expected = new PackingJobArtifactStore.TemporaryUrl(
                                                                                                 URI.create("https://storage.example/medium.glb?sig=secret"),
                                                                                                 NOW.plus(Duration.ofMinutes(10)));
        givenReadAccess();
        when(repository.findById(job.id())).thenReturn(Optional.of(job));
        when(artifacts.createPreviewUrl(job.id(), PreviewQuality.MEDIUM, Duration.ofMinutes(10), null)).thenReturn(expected);

        PackingJobArtifactStore.TemporaryUrl result = service.preparePreviewDownload(
                                                                                     previewQuery(jobId, PreviewQuality.MEDIUM, false));

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void preparePreviewDownloadNamesTheAttachmentAfterTheJobAndQuality() {
        UUID jobId = UUID.randomUUID();
        PackingJob job = job(jobId, PROJECT_ID, PackingJobStatus.SUCCEEDED, readyPreview());
        givenReadAccess();
        when(repository.findById(job.id())).thenReturn(Optional.of(job));

        service.preparePreviewDownload(previewQuery(jobId, PreviewQuality.HIGH, true));

        verify(artifacts).createPreviewUrl(job.id(),
                                           PreviewQuality.HIGH,
                                           Duration.ofMinutes(10),
                                           "packing-job-" + jobId + "-high.glb");
    }

    @Test
    void preparePreviewDownloadRejectsAPendingPreviewAndAQualityThatWasNeverStored() {
        UUID pendingId = UUID.randomUUID();
        UUID readyId = UUID.randomUUID();
        PackingPreview highOnly = PackingPreview.ready(List.of(new PreviewModel(PreviewQuality.HIGH, 12, 640)));
        givenReadAccess();
        when(repository.findById(new com.packing.backend.domain.packing.PackingJobId(pendingId)))
                                                                                                 .thenReturn(Optional.of(job(pendingId,
                                                                                                                             PROJECT_ID,
                                                                                                                             PackingJobStatus.SUCCEEDED,
                                                                                                                             PackingPreview.PENDING)));
        when(repository.findById(new com.packing.backend.domain.packing.PackingJobId(readyId)))
                                                                                               .thenReturn(Optional.of(job(readyId,
                                                                                                                           PROJECT_ID,
                                                                                                                           PackingJobStatus.SUCCEEDED,
                                                                                                                           highOnly)));

        assertThatThrownBy(() -> service.preparePreviewDownload(previewQuery(pendingId, PreviewQuality.HIGH, false)))
                                                                                                                     .isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(() -> service.preparePreviewDownload(previewQuery(readyId, PreviewQuality.LOW, false)))
                                                                                                                  .isInstanceOf(ResourceNotFoundException.class);
        verify(artifacts, never()).createPreviewUrl(any(), any(), any(), any());
    }

    @Test
    void preparePreviewDownloadHidesAJobFromAnotherProject() {
        UUID jobId = UUID.randomUUID();
        givenReadAccess();
        when(repository.findById(new com.packing.backend.domain.packing.PackingJobId(jobId)))
                                                                                             .thenReturn(Optional.of(job(jobId,
                                                                                                                         OTHER_PROJECT_ID,
                                                                                                                         PackingJobStatus.SUCCEEDED,
                                                                                                                         readyPreview())));

        assertThatThrownBy(() -> service.preparePreviewDownload(previewQuery(jobId, PreviewQuality.HIGH, false)))
                                                                                                                 .isInstanceOf(PackingJobNotFoundException.class);
        verify(artifacts, never()).createPreviewUrl(any(), any(), any(), any());
    }

    @Test
    void preparePreviewDownloadRejectsANonMemberBeforeLoadingTheJob() {
        when(access.findAccess(new FirebaseUid(FIREBASE_UID), PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.preparePreviewDownload(previewQuery(UUID.randomUUID(), PreviewQuality.HIGH, false)))
                                                                                                                             .isInstanceOf(ProjectNotFoundException.class);
        verify(repository, never()).findById(any());
    }

    @Test
    void prepareResultDownloadHidesAJobFromAnotherProjectWithoutLookingUpArtifacts() {
        UUID jobId = UUID.randomUUID();
        givenReadAccess();
        when(repository.findById(new com.packing.backend.domain.packing.PackingJobId(jobId)))
                                                                                             .thenReturn(Optional.of(job(jobId, OTHER_PROJECT_ID, PackingJobStatus.SUCCEEDED)));

        assertThatThrownBy(() -> service.prepareResultDownload(
                                                               new PackingJobResultQuery(FIREBASE_UID, PROJECT_ID.value(), jobId)))
                                                                                                                                   .isInstanceOf(PackingJobNotFoundException.class);

        verify(artifacts, never()).findResult(any());
        verify(artifacts, never()).createResultDownloadUrl(any(), any());
    }

    @Test
    void prepareResultDownloadMapsAnUnknownJobToNotFoundWithoutLookingUpArtifacts() {
        UUID jobId = UUID.randomUUID();
        givenReadAccess();
        when(repository.findById(new com.packing.backend.domain.packing.PackingJobId(jobId)))
                                                                                             .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepareResultDownload(
                                                               new PackingJobResultQuery(FIREBASE_UID, PROJECT_ID.value(), jobId)))
                                                                                                                                   .isInstanceOf(PackingJobNotFoundException.class);

        verify(artifacts, never()).findResult(any());
        verify(artifacts, never()).createResultDownloadUrl(any(), any());
    }

    @Test
    void prepareResultDownloadRejectsANonMemberBeforeLoadingTheJobOrArtifacts() {
        UUID jobId = UUID.randomUUID();
        when(access.findAccess(new FirebaseUid(FIREBASE_UID), PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepareResultDownload(
                                                               new PackingJobResultQuery(FIREBASE_UID, PROJECT_ID.value(), jobId)))
                                                                                                                                   .isInstanceOf(ProjectNotFoundException.class);

        verify(repository, never()).findById(any());
        verify(artifacts, never()).findResult(any());
        verify(artifacts, never()).createResultDownloadUrl(any(), any());
    }

    @Test
    void prepareResultDownloadRejectsQueuedJobsWithoutLookingUpArtifacts() {
        UUID jobId = UUID.randomUUID();
        givenReadAccess();
        when(repository.findById(new com.packing.backend.domain.packing.PackingJobId(jobId)))
                                                                                             .thenReturn(Optional.of(job(jobId, PROJECT_ID, PackingJobStatus.QUEUED)));

        assertThatThrownBy(() -> service.prepareResultDownload(
                                                               new PackingJobResultQuery(FIREBASE_UID, PROJECT_ID.value(), jobId)))
                                                                                                                                   .isInstanceOf(ResourceConflictException.class);

        verify(artifacts, never()).findResult(any());
        verify(artifacts, never()).createResultDownloadUrl(any(), any());
    }

    @Test
    void prepareResultDownloadRejectsRunningAndFailedJobsWithoutLookingUpArtifacts() {
        for (PackingJobStatus status : List.of(PackingJobStatus.RUNNING, PackingJobStatus.FAILED)) {
            UUID jobId = UUID.randomUUID();
            givenReadAccess();
            when(repository.findById(new com.packing.backend.domain.packing.PackingJobId(jobId)))
                                                                                                 .thenReturn(Optional.of(job(jobId, PROJECT_ID, status)));

            assertThatThrownBy(() -> service.prepareResultDownload(
                                                                   new PackingJobResultQuery(FIREBASE_UID, PROJECT_ID.value(), jobId)))
                                                                                                                                       .isInstanceOf(ResourceConflictException.class);
        }

        verify(artifacts, never()).findResult(any());
        verify(artifacts, never()).createResultDownloadUrl(any(), any());
    }

    @Test
    void prepareResultDownloadRejectsASucceededJobWhoseResultBlobIsMissing() {
        UUID jobId = UUID.randomUUID();
        PackingJob job = job(jobId, PROJECT_ID, PackingJobStatus.SUCCEEDED);
        givenReadAccess();
        when(repository.findById(job.id())).thenReturn(Optional.of(job));
        when(artifacts.findResult(job.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepareResultDownload(
                                                               new PackingJobResultQuery(FIREBASE_UID, PROJECT_ID.value(), jobId)))
                                                                                                                                   .isInstanceOf(ResourceNotFoundException.class);

        verify(artifacts, never()).createResultDownloadUrl(any(), any());
    }

    @Test
    void cancelStopsARunningJobAndPublishesTheCancellation() {
        UUID jobId = UUID.randomUUID();
        PackingJob job = job(jobId, PROJECT_ID, PackingJobStatus.RUNNING);
        PackingJobView expected = view(jobId, PROJECT_ID.value());
        givenAccess(ProjectPermission.WRITE);
        when(repository.findById(job.id())).thenReturn(Optional.of(job));
        when(finder.detailInProject(PROJECT_ID, job.id())).thenReturn(Optional.of(expected));

        PackingJobView result = service.cancel(cancel(jobId));

        assertThat(result).isEqualTo(expected);
        assertThat(job.status()).isEqualTo(PackingJobStatus.CANCELLED);
        assertThat(job.finishedAt()).isEqualTo(NOW);
        InOrder order = inOrder(repository, events);
        order.verify(repository)
             .save(job);
        order.verify(events)
             .publishAll(List.of(new PackingJobCancelled(job.id(), PROJECT_ID, NOW)));
    }

    @Test
    void cancelOfAnAlreadyCancelledJobChangesNothing() {
        UUID jobId = UUID.randomUUID();
        PackingJob job = job(jobId, PROJECT_ID, PackingJobStatus.CANCELLED);
        givenAccess(ProjectPermission.WRITE);
        when(repository.findById(job.id())).thenReturn(Optional.of(job));
        when(finder.detailInProject(PROJECT_ID, job.id())).thenReturn(Optional.of(view(jobId, PROJECT_ID.value())));

        service.cancel(cancel(jobId));

        verify(repository, never()).save(any());
        verify(events, never()).publishAll(any());
    }

    @Test
    void cancelOfAFinishedJobIsAConflict() {
        for (PackingJobStatus finished : List.of(PackingJobStatus.SUCCEEDED, PackingJobStatus.FAILED)) {
            UUID jobId = UUID.randomUUID();
            PackingJob job = job(jobId, PROJECT_ID, finished);
            givenAccess(ProjectPermission.WRITE);
            when(repository.findById(job.id())).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> service.cancel(cancel(jobId))).isInstanceOf(ResourceConflictException.class);

            assertThat(job.status()).isEqualTo(finished);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void cancelNeedsWritePermissionBeforeLoadingTheJob() {
        givenReadAccess();

        assertThatThrownBy(() -> service.cancel(cancel(UUID.randomUUID()))).isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void cancelIsRefusedWhileTheProjectIsDisabled() {
        when(access.findAccess(new FirebaseUid(FIREBASE_UID), PROJECT_ID)).thenReturn(Optional.of(
                                                                                                  new ProjectAccess(USER_ID,
                                                                                                                    PROJECT_ID,
                                                                                                                    ProjectStatus.DISABLED,
                                                                                                                    ProjectPermission.WRITE)));

        assertThatThrownBy(() -> service.cancel(cancel(UUID.randomUUID()))).isInstanceOf(ResourceConflictException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void cancelHidesAJobFromAnotherProject() {
        UUID jobId = UUID.randomUUID();
        PackingJob job = job(jobId, OTHER_PROJECT_ID, PackingJobStatus.RUNNING);
        givenAccess(ProjectPermission.WRITE);
        when(repository.findById(job.id())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.cancel(cancel(jobId))).isInstanceOf(PackingJobNotFoundException.class);

        assertThat(job.status()).isEqualTo(PackingJobStatus.RUNNING);
        verify(repository, never()).save(any());
    }

    @Test
    void inputSchemaIsTheAdaptersDocument() {
        PackingInputSchema.SchemaDocument document = new PackingInputSchema.SchemaDocument(1, "{}", "e".repeat(64));
        when(schema.document()).thenReturn(document);

        assertThat(service.inputSchema()).isSameAs(document);
    }

    @Test
    void draftFromTheCallersLatestJobKeepsUsableFilesUnderTheirCurrentNames() {
        UUID jobId = UUID.randomUUID();
        FileId bunnyId = FileId.generate();
        FileId goneId = FileId.generate();
        StoredFile renamed = StoredFile.upload(bunnyId, USER_ID, PROJECT_ID, new FileName("bunny-v2.obj"), 1_024, new Checksum("a".repeat(64)), NOW);
        givenReadAccess();
        when(finder.latestOwnInProject(PROJECT_ID, USER_ID)).thenReturn(Optional.of(storedJob(jobId,
                                                                                              new PackingJobInputView.Item(bunnyId.value(), "bunny.obj", 3),
                                                                                              new PackingJobInputView.Item(goneId.value(), "gone.obj", 1))));
        when(schema.acceptedFormats()).thenReturn(Set.of(ModelFormat.OBJ));
        when(files.findById(bunnyId)).thenReturn(Optional.of(renamed));
        when(files.findById(goneId)).thenReturn(Optional.empty());
        when(schema.reconcile(new Settings(120, PARAMETERS))).thenReturn(new Reconciled(new Settings(120, PARAMETERS), List.of()));
        when(schema.document()).thenReturn(SCHEMA_V1);

        PackingJobDraft draft = service.draft(new DraftQuery(FIREBASE_UID, PROJECT_ID.value(), null));

        assertThat(draft).isEqualTo(new PackingJobDraft(DraftSource.LAST_OWN_JOB,
                                                        jobId,
                                                        new PackingJobInputView(1,
                                                                                120,
                                                                                PARAMETERS.json(),
                                                                                List.of(new PackingJobInputView.Item(bunnyId.value(), "bunny-v2.obj", 3))),
                                                        List.of(new OmittedItem(goneId.value(), "gone.obj")),
                                                        List.of()));
        verify(finder, never()).latestInProject(any());
    }

    @Test
    void draftFallsBackToTheProjectsLatestJob() {
        UUID jobId = UUID.randomUUID();
        givenReadAccess();
        when(finder.latestOwnInProject(PROJECT_ID, USER_ID)).thenReturn(Optional.empty());
        when(finder.latestInProject(PROJECT_ID)).thenReturn(Optional.of(storedJob(jobId)));
        when(schema.acceptedFormats()).thenReturn(Set.of(ModelFormat.OBJ));
        when(schema.reconcile(any())).thenReturn(new Reconciled(new Settings(120, PARAMETERS), List.of()));
        when(schema.document()).thenReturn(SCHEMA_V1);

        PackingJobDraft draft = service.draft(new DraftQuery(FIREBASE_UID, PROJECT_ID.value(), null));

        assertThat(draft.source()).isEqualTo(DraftSource.LAST_PROJECT_JOB);
        assertThat(draft.sourceJobId()).isEqualTo(jobId);
    }

    @Test
    void draftUsesTheSchemaDefaultsWhenTheProjectHasNoStructuredJob() {
        givenReadAccess();
        when(finder.latestOwnInProject(PROJECT_ID, USER_ID)).thenReturn(Optional.empty());
        when(finder.latestInProject(PROJECT_ID)).thenReturn(Optional.empty());
        when(schema.defaults()).thenReturn(new Settings(60, new PackingParameters("{}")));
        when(schema.document()).thenReturn(SCHEMA_V1);

        PackingJobDraft draft = service.draft(new DraftQuery(FIREBASE_UID, PROJECT_ID.value(), null));

        assertThat(draft).isEqualTo(new PackingJobDraft(DraftSource.DEFAULTS,
                                                        null,
                                                        new PackingJobInputView(1, 60, "{}", List.of()),
                                                        List.of(),
                                                        List.of()));
    }

    @Test
    void draftFromASelectedJobIgnoresTheCallersHistory() {
        UUID jobId = UUID.randomUUID();
        givenReadAccess();
        when(finder.detailInProject(PROJECT_ID, new PackingJobId(jobId))).thenReturn(Optional.of(storedJob(jobId)));
        when(schema.acceptedFormats()).thenReturn(Set.of(ModelFormat.OBJ));
        when(schema.reconcile(any())).thenReturn(new Reconciled(new Settings(120, PARAMETERS), List.of("/maxRuntimeSeconds")));
        when(schema.document()).thenReturn(SCHEMA_V1);

        PackingJobDraft draft = service.draft(new DraftQuery(FIREBASE_UID, PROJECT_ID.value(), jobId));

        assertThat(draft.source()).isEqualTo(DraftSource.SELECTED_JOB);
        assertThat(draft.resetFields()).containsExactly("/maxRuntimeSeconds");
        verify(finder, never()).latestOwnInProject(any(), any());
    }

    @Test
    void draftFromAJobOutsideTheProjectIsNotFound() {
        UUID jobId = UUID.randomUUID();
        givenReadAccess();
        when(finder.detailInProject(PROJECT_ID, new PackingJobId(jobId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.draft(new DraftQuery(FIREBASE_UID, PROJECT_ID.value(), jobId)))
                                                                                                        .isInstanceOf(PackingJobNotFoundException.class);
    }

    @Test
    void draftFromALegacyJobIsAConflict() {
        UUID jobId = UUID.randomUUID();
        givenReadAccess();
        when(finder.detailInProject(PROJECT_ID, new PackingJobId(jobId))).thenReturn(Optional.of(view(jobId, PROJECT_ID.value())));

        assertThatThrownBy(() -> service.draft(new DraftQuery(FIREBASE_UID, PROJECT_ID.value(), jobId)))
                                                                                                        .isInstanceOf(ResourceConflictException.class)
                                                                                                        .hasMessage("This job predates structured input and cannot be re-run");
    }

    @Test
    void draftOmitsAFileTheEngineNoLongerAccepts() {
        UUID jobId = UUID.randomUUID();
        FileId bracketId = FileId.generate();
        StoredFile bracket = StoredFile.upload(bracketId, USER_ID, PROJECT_ID, new FileName("bracket.stl"), 1_024, new Checksum("a".repeat(64)), NOW);
        givenReadAccess();
        when(finder.latestOwnInProject(PROJECT_ID, USER_ID)).thenReturn(Optional.of(storedJob(jobId,
                                                                                              new PackingJobInputView.Item(bracketId.value(), "bracket.stl", 2))));
        when(schema.acceptedFormats()).thenReturn(Set.of(ModelFormat.OBJ));
        when(files.findById(bracketId)).thenReturn(Optional.of(bracket));
        when(schema.reconcile(any())).thenReturn(new Reconciled(new Settings(120, PARAMETERS), List.of()));
        when(schema.document()).thenReturn(SCHEMA_V1);

        PackingJobDraft draft = service.draft(new DraftQuery(FIREBASE_UID, PROJECT_ID.value(), null));

        assertThat(draft.input()
                        .items()).isEmpty();
        assertThat(draft.omittedItems()).containsExactly(new OmittedItem(bracketId.value(), "bracket.stl"));
    }

    @Test
    void draftRequiresMembership() {
        when(access.findAccess(new FirebaseUid(FIREBASE_UID), PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.draft(new DraftQuery(FIREBASE_UID, PROJECT_ID.value(), null)))
                                                                                                       .isInstanceOf(ProjectNotFoundException.class);
    }

    private static PackingJobView storedJob(UUID jobId, PackingJobInputView.Item... items) {
        return new PackingJobView(jobId,
                                  PROJECT_ID.value(),
                                  PackingJobStatus.SUCCEEDED,
                                  "v0.0.2",
                                  "a".repeat(64),
                                  NOW,
                                  NOW,
                                  NOW,
                                  null,
                                  "packed.obj",
                                  "model/obj",
                                  12L,
                                  "b".repeat(64),
                                  List.of(),
                                  new PackingJobInputView(1, 120, PARAMETERS.json(), List.of(items)),
                                  PackingPreview.PENDING);
    }

    private void givenReadAccess() {
        givenAccess(ProjectPermission.READ);
    }

    private void givenAccess(ProjectPermission permission) {
        when(access.findAccess(new FirebaseUid(FIREBASE_UID), PROJECT_ID)).thenReturn(Optional.of(projectAccess(permission)));
    }

    private static CreatePackingJobCommand create() {
        return new CreatePackingJobCommand(FIREBASE_UID, PROJECT_ID.value(), BODY);
    }

    private static CancelPackingJobCommand cancel(UUID jobId) {
        return new CancelPackingJobCommand(FIREBASE_UID, PROJECT_ID.value(), jobId);
    }

    private static ValidatedInput validated(RequestedItem... items) {
        return new ValidatedInput(1, List.of(items), 120, PARAMETERS);
    }

    private static StoredFile file(ProjectId projectId, String name) {
        return StoredFile.upload(FileId.generate(),
                                 USER_ID,
                                 projectId,
                                 new FileName(name),
                                 1_024,
                                 new Checksum("a".repeat(64)),
                                 NOW);
    }

    private static ProjectAccess projectAccess(ProjectPermission permission) {
        return new ProjectAccess(USER_ID, PROJECT_ID, ProjectStatus.ACTIVE, permission);
    }

    private static PackingJobView view(UUID id, UUID projectId) {
        return new PackingJobView(id,
                                  projectId,
                                  PackingJobStatus.QUEUED,
                                  null,
                                  null,
                                  NOW,
                                  null,
                                  null,
                                  null,
                                  null,
                                  null,
                                  null,
                                  null,
                                  List.of(),
                                  null,
                                  PackingPreview.NONE);
    }

    private static PackingJob job(UUID id, ProjectId projectId, PackingJobStatus status) {
        return job(id, projectId, status, PackingPreview.NONE);
    }

    private static PackingJob job(UUID id, ProjectId projectId, PackingJobStatus status, PackingPreview preview) {
        return job(id, projectId, status, preview, List.of());
    }

    private static PackingJob job(UUID id, ProjectId projectId, PackingJobStatus status, PackingPreview preview, List<ResultBin> bins) {
        return PackingJob.rehydrate(new com.packing.backend.domain.packing.PackingJobId(id),
                                    projectId,
                                    USER_ID,
                                    PackingInputs.single(),
                                    60,
                                    status,
                                    "1.0.0",
                                    "a".repeat(64),
                                    null,
                                    null,
                                    NOW,
                                    "result.bin",
                                    "application/octet-stream",
                                    "b".repeat(64),
                                    12L,
                                    bins,
                                    status == PackingJobStatus.FAILED ? "packing failed" : null,
                                    preview,
                                    0,
                                    NOW);
    }

    private static PackingJobPreviewQuery previewQuery(UUID jobId, PreviewQuality quality, boolean download) {
        return new PackingJobPreviewQuery(FIREBASE_UID, PROJECT_ID.value(), jobId, quality, download);
    }

    private static final List<ResultBin> TWO_BINS = List.of(new ResultBin(7, "1".repeat(64)), new ResultBin(5, "2".repeat(64)));

    private static PackingPreview readyPreview() {
        return PackingPreview.ready(List.of(new PreviewModel(PreviewQuality.MEDIUM, 3, 320),
                                            new PreviewModel(PreviewQuality.HIGH, 12, 640)));
    }

    private static PackingJobArtifactStore.ResultArtifact resultArtifact() {
        return new PackingJobArtifactStore.ResultArtifact("result.bin",
                                                          "application/octet-stream",
                                                          12L,
                                                          "b".repeat(64),
                                                          "1.0.0",
                                                          "a".repeat(64));
    }
}
