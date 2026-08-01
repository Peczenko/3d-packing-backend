package com.packing.backend.core.packing;

import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.core.project.port.out.ProjectAccessLookup;
import com.packing.backend.core.project.port.out.ProjectAccessLookup.ProjectAccess;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.PageRequest;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectNotFoundException;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.shared.ResourceNotFoundException;
import com.packing.backend.domain.user.FirebaseUid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class PackingJobApplicationService {

    public static final long DEFAULT_MAX_RUNTIME_SECONDS = 60;
    public static final long MAX_RUNTIME_SECONDS = 7200;

    private final PackingJobRepository jobs;
    private final PackingJobFinder finder;
    private final ProjectAccessLookup access;
    private final DomainEventPublisher events;
    private final PackingJobArtifactStore artifacts;
    private final Clock clock;

    public PackingJobView create(CreatePackingJobCommand command) {
        ProjectAccess project = requireAccess(command.firebaseUid(), command.projectId(),
                ProjectPermission.WRITE).requireWritable();
        PackingJob job = PackingJob.queue(PackingJobId.generate(), project.projectId(),
                project.userId(), command.specJson(), command.maxRuntimeSeconds(), clock.instant());
        jobs.save(job);
        events.publishAll(job.pullDomainEvents());
        return requireView(project.projectId(), job.id());
    }

    @Transactional(readOnly = true)
    public Page<PackingJobView> list(ListPackingJobsQuery query) {
        ProjectAccess project = requireAccess(query.firebaseUid(), query.projectId(),
                ProjectPermission.READ);
        return finder.listInProject(project.projectId(), query.page());
    }

    @Transactional(readOnly = true)
    public PackingJobView get(PackingJobQuery query) {
        ProjectAccess project = requireAccess(query.firebaseUid(), query.projectId(),
                ProjectPermission.READ);
        return requireView(project.projectId(), new PackingJobId(query.jobId()));
    }

    @Transactional(readOnly = true)
    public PackingJobArtifactStore.TemporaryUrl prepareResultDownload(PackingJobResultQuery query) {
        ProjectAccess project = requireAccess(query.firebaseUid(), query.projectId(),
                ProjectPermission.READ);
        PackingJobId jobId = new PackingJobId(query.jobId());
        PackingJob job = jobs.findById(jobId)
                .filter(candidate -> candidate.projectId().equals(project.projectId()))
                .orElseThrow(() -> PackingJobNotFoundException.byId(jobId));
        if (job.status() != PackingJobStatus.SUCCEEDED) {
            throw new ResourceConflictException(
                    "Packing job result is not available until the job succeeds");
        }
        artifacts.findResult(jobId).orElseThrow(() -> new ResourceNotFoundException(
                "Result artifact for packing job " + jobId + " was not found"));
        return artifacts.createResultDownloadUrl(jobId, Duration.ofMinutes(10));
    }

    private ProjectAccess requireAccess(String firebaseUid,
                                        UUID projectId,
                                        ProjectPermission required) {
        ProjectId id = new ProjectId(projectId);
        return access.findAccess(new FirebaseUid(firebaseUid), id)
                .orElseThrow(() -> ProjectNotFoundException.byId(id))
                .requireAtLeast(required);
    }

    private PackingJobView requireView(ProjectId projectId, PackingJobId jobId) {
        return finder.detailInProject(projectId, jobId)
                .orElseThrow(() -> PackingJobNotFoundException.byId(jobId));
    }

    public record CreatePackingJobCommand(String firebaseUid,
                                          UUID projectId,
                                          String specJson,
                                          long maxRuntimeSeconds) {
    }

    public record ListPackingJobsQuery(String firebaseUid, UUID projectId, PageRequest page) {
    }

    public record PackingJobQuery(String firebaseUid, UUID projectId, UUID jobId) {
    }

    public record PackingJobResultQuery(String firebaseUid, UUID projectId, UUID jobId) {
    }
}
