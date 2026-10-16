package com.packing.backend.core.packing;

import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.packing.port.out.PackingInputSchema;
import com.packing.backend.core.packing.port.out.PackingInputSchema.RequestedItem;
import com.packing.backend.core.packing.port.out.PackingInputSchema.ValidatedInput;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.core.project.port.out.ProjectAccessLookup;
import com.packing.backend.core.project.port.out.ProjectAccessLookup.ProjectAccess;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.port.out.DomainEventPublisher;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.packing.PackingInput;
import com.packing.backend.domain.packing.PackingItem;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class PackingJobApplicationService {

    private static final String FILE_NOT_AVAILABLE = "File is not available in this project";

    private final PackingJobRepository    jobs;
    private final PackingJobFinder        finder;
    private final ProjectAccessLookup     access;
    private final DomainEventPublisher    events;
    private final PackingJobArtifactStore artifacts;
    private final PackingInputSchema      schema;
    private final FileRepository          files;
    private final Clock                   clock;

    public PackingJobView create(CreatePackingJobCommand command) {
        ProjectAccess project = requireAccess(command.firebaseUid(),
                                              command.projectId(),
                                              ProjectPermission.WRITE).requireWritable();
        ValidatedInput validated = schema.validate(command.body());
        PackingInput input = new PackingInput(validated.schemaVersion(),
                                              resolveItems(project.projectId(), validated.items()),
                                              validated.parameters());
        PackingJob job = PackingJob.queue(PackingJobId.generate(),
                                          project.projectId(),
                                          project.userId(),
                                          input,
                                          validated.maxRuntimeSeconds(),
                                          clock.instant());
        jobs.save(job);
        events.publishAll(job.pullDomainEvents());
        return requireView(project.projectId(), job.id());
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public PackingInputSchema.SchemaDocument inputSchema() {
        return schema.document();
    }

    @Transactional(readOnly = true)
    public Page<PackingJobView> list(ListPackingJobsQuery query) {
        ProjectAccess project = requireAccess(query.firebaseUid(),
                                              query.projectId(),
                                              ProjectPermission.READ);
        return finder.listInProject(project.projectId(), query.criteria());
    }

    @Transactional(readOnly = true)
    public PackingJobView get(PackingJobQuery query) {
        ProjectAccess project = requireAccess(query.firebaseUid(),
                                              query.projectId(),
                                              ProjectPermission.READ);
        return requireView(project.projectId(), new PackingJobId(query.jobId()));
    }

    @Transactional(readOnly = true)
    public PackingJobArtifactStore.TemporaryUrl prepareResultDownload(PackingJobResultQuery query) {
        ProjectAccess project = requireAccess(query.firebaseUid(),
                                              query.projectId(),
                                              ProjectPermission.READ);
        PackingJobId jobId = new PackingJobId(query.jobId());
        PackingJob job = jobs.findById(jobId)
                             .filter(candidate -> candidate.projectId()
                                                           .equals(project.projectId()))
                             .orElseThrow(() -> PackingJobNotFoundException.byId(jobId));
        if (job.status() != PackingJobStatus.SUCCEEDED) {
            throw new ResourceConflictException(
                                                "Packing job result is not available until the job succeeds");
        }
        artifacts.findResult(jobId)
                 .orElseThrow(() -> new ResourceNotFoundException(
                                                                  "Result artifact for packing job " + jobId + " was not found"));
        return artifacts.createResultDownloadUrl(jobId, Duration.ofMinutes(10));
    }

    private List<PackingItem> resolveItems(ProjectId projectId, List<RequestedItem> requested) {
        Set<ModelFormat> accepted = schema.acceptedFormats();
        Map<String, String> errors = new LinkedHashMap<>();
        List<PackingItem> items = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            RequestedItem item = requested.get(index);
            String pointer = "/items/" + index + "/fileId";
            Optional<StoredFile> file = availableFile(projectId, item.fileId());
            if (file.isEmpty()) {
                errors.put(pointer, FILE_NOT_AVAILABLE);
            } else if (!accepted.contains(file.get()
                                              .format())) {
                errors.put(pointer, formatNotAccepted(file.get(), accepted));
            } else {
                items.add(PackingItem.of(file.get(), item.copies()));
            }
        }
        if (!errors.isEmpty()) {
            throw new PackingInputRejectedException(errors);
        }
        return items;
    }

    private Optional<StoredFile> availableFile(ProjectId projectId, FileId fileId) {
        return files.findById(fileId)
                    .filter(file -> file.belongsTo(projectId) && !file.isDeleted());
    }

    private static String formatNotAccepted(StoredFile file, Set<ModelFormat> accepted) {
        return file.name()
                   .value()
                + " is " + file.format()
                               .name()
                + "; the packing engine accepts " + accepted.stream()
                                                            .map(ModelFormat::name)
                                                            .sorted()
                                                            .collect(Collectors.joining(", "));
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

    public record CreatePackingJobCommand(String firebaseUid, UUID projectId, String body) {
    }

    public record ListPackingJobsQuery(String firebaseUid, UUID projectId, PackingJobListCriteria criteria) {
    }

    public record PackingJobQuery(String firebaseUid, UUID projectId, UUID jobId) {
    }

    public record PackingJobResultQuery(String firebaseUid, UUID projectId, UUID jobId) {
    }
}
