package com.packing.backend.core.packing;

import com.packing.backend.core.packing.PackingJobApplicationService.CreatePackingJobCommand;
import com.packing.backend.core.packing.PackingJobApplicationService.ListPackingJobsQuery;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobQuery;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobResultQuery;
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
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import com.packing.backend.domain.packing.PackingJobStatus;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackingJobApplicationServiceTest {

    private static final Instant                NOW              = Instant.parse("2026-08-01T10:15:30Z");
    private static final String                 FIREBASE_UID     = "firebase-1";
    private static final ProjectId              PROJECT_ID       = ProjectId.generate();
    private static final ProjectId              OTHER_PROJECT_ID = ProjectId.generate();
    private static final UserId                 USER_ID          = UserId.generate();
    private static final PageRequest            PAGE             = new PageRequest(0, 20);
    private static final PackingJobListCriteria CRITERIA         = new PackingJobListCriteria(
                                                                                              PAGE,
                                                                                              "engine",
                                                                                              Set.of(PackingJobStatus.RUNNING),
                                                                                              new InstantRange(NOW.minusSeconds(60), NOW.plusSeconds(60)),
                                                                                              new InstantRange(null, null),
                                                                                              new InstantRange(null, null),
                                                                                              PackingJobListCriteria.SortField.CREATED_AT,
                                                                                              SortDirection.DESC);

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

    private PackingJobApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PackingJobApplicationService(repository,
                                                   finder,
                                                   access,
                                                   events,
                                                   artifacts,
                                                   Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createRequiresWritableProjectAndPublishesQueuedEventAfterSaving() {
        PackingJobView expected = view(UUID.randomUUID(), PROJECT_ID.value());
        when(access.findAccess(new FirebaseUid(FIREBASE_UID), PROJECT_ID)).thenReturn(
                                                                                      Optional.of(projectAccess(ProjectPermission.WRITE)));
        when(finder.detailInProject(eq(PROJECT_ID), any())).thenReturn(Optional.of(expected));

        PackingJobView result = service.create(new CreatePackingJobCommand(
                                                                           FIREBASE_UID,
                                                                           PROJECT_ID.value(),
                                                                           "{\"testField\":true}",
                                                                           60));

        InOrder order = inOrder(repository, events);
        order.verify(repository)
             .save(org.mockito.ArgumentMatchers.argThat(job -> job.status() == PackingJobStatus.QUEUED
                     && job.projectId()
                           .equals(PROJECT_ID)
                     && job.requestedBy()
                           .equals(USER_ID)
                     && job.createdAt()
                           .equals(NOW)));
        order.verify(events)
             .publishAll(org.mockito.ArgumentMatchers.argThat(published -> published.size() == 1 && published.iterator()
                                                                                                             .next() instanceof PackingJobQueued));
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void createRejectsReadOnlyProjectWithoutSavingOrPublishing() {
        when(access.findAccess(new FirebaseUid(FIREBASE_UID), PROJECT_ID)).thenReturn(
                                                                                      Optional.of(projectAccess(ProjectPermission.READ)));

        assertThatThrownBy(() -> service.create(new CreatePackingJobCommand(
                                                                            FIREBASE_UID,
                                                                            PROJECT_ID.value(),
                                                                            "{}",
                                                                            60)))
                                                                                 .isInstanceOf(PermissionDeniedException.class);

        verify(repository, never()).save(any());
        verify(events, never()).publishAll(any());
    }

    @Test
    void createIsRefusedWhileTheProjectIsDisabled() {
        when(access.findAccess(new FirebaseUid(FIREBASE_UID), PROJECT_ID)).thenReturn(Optional.of(
                                                                                                  new ProjectAccess(USER_ID,
                                                                                                                    PROJECT_ID,
                                                                                                                    ProjectStatus.DISABLED,
                                                                                                                    ProjectPermission.WRITE)));

        assertThatThrownBy(() -> service.create(new CreatePackingJobCommand(
                                                                            FIREBASE_UID,
                                                                            PROJECT_ID.value(),
                                                                            "{}",
                                                                            60)))
                                                                                 .isInstanceOf(ResourceConflictException.class);

        verify(repository, never()).save(any());
        verify(events, never()).publishAll(any());
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

    private void givenReadAccess() {
        when(access.findAccess(new FirebaseUid(FIREBASE_UID), PROJECT_ID)).thenReturn(
                                                                                      Optional.of(projectAccess(ProjectPermission.READ)));
    }

    private static ProjectAccess projectAccess(ProjectPermission permission) {
        return new ProjectAccess(USER_ID, PROJECT_ID, ProjectStatus.ACTIVE, permission);
    }

    private static PackingJobView view(UUID id, UUID projectId) {
        return new PackingJobView(id,
                                  projectId,
                                  PackingJobStatus.QUEUED,
                                  60,
                                  null,
                                  null,
                                  NOW,
                                  null,
                                  null,
                                  null,
                                  null,
                                  null,
                                  null,
                                  null);
    }

    private static PackingJob job(UUID id, ProjectId projectId, PackingJobStatus status) {
        return PackingJob.rehydrate(new com.packing.backend.domain.packing.PackingJobId(id),
                                    projectId,
                                    USER_ID,
                                    "{}",
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
                                    status == PackingJobStatus.FAILED ? "packing failed" : null,
                                    0,
                                    NOW);
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
