package com.packing.backend.core.packing;

import com.packing.backend.core.file.port.out.FileRepository;
import com.packing.backend.core.packing.port.out.PackingArtifact;
import com.packing.backend.core.packing.port.out.PackingInputSchema;
import com.packing.backend.core.packing.port.out.PackingInputSchema.Reconciled;
import com.packing.backend.core.packing.port.out.PackingInputSchema.RequestedItem;
import com.packing.backend.core.packing.port.out.PackingInputSchema.Settings;
import com.packing.backend.core.packing.port.out.PackingInputSchema.ValidatedInput;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.core.packing.port.out.PackingJobFinder;
import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.core.project.ProjectAccessGuard;
import com.packing.backend.core.project.port.out.ProjectAccessLookup.ProjectAccess;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.TemporaryUrl;
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
import com.packing.backend.domain.packing.PackingParameters;
import com.packing.backend.domain.packing.PreviewModel;
import com.packing.backend.domain.packing.PreviewQuality;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.shared.ResourceNotFoundException;
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

    private static final String   FILE_NOT_AVAILABLE    = "File is not available in this project";
    private static final Duration DOWNLOAD_URL_VALIDITY = Duration.ofMinutes(10);

    private final PackingJobRepository    jobs;
    private final PackingJobFinder        finder;
    private final ProjectAccessGuard      access;
    private final DomainEventPublisher    events;
    private final PackingJobArtifactStore artifacts;
    private final PackingInputSchema      schema;
    private final FileRepository          files;
    private final Clock                   clock;

    public PackingJobView create(CreatePackingJobCommand command) {
        ProjectAccess project = access.requireWritable(command.firebaseUid(), command.projectId());
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

    public PackingJobView cancel(CancelPackingJobCommand command) {
        ProjectAccess project = access.requireWritable(command.firebaseUid(), command.projectId());
        PackingJob job = requireJob(project.projectId(), new PackingJobId(command.jobId()));
        job.requireCancellable();
        if (job.cancel(clock.instant())) {
            jobs.save(job);
            events.publishAll(job.pullDomainEvents());
        }
        return requireView(project.projectId(), job.id());
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public PackingInputSchema.SchemaDocument inputSchema() {
        return schema.document();
    }

    @Transactional(readOnly = true)
    public Page<PackingJobView> list(ListPackingJobsQuery query) {
        ProjectAccess project = access.require(query.firebaseUid(), query.projectId(), ProjectPermission.READ);
        return finder.listInProject(project.projectId(), query.criteria());
    }

    @Transactional(readOnly = true)
    public PackingJobView get(PackingJobQuery query) {
        ProjectAccess project = access.require(query.firebaseUid(), query.projectId(), ProjectPermission.READ);
        return requireView(project.projectId(), new PackingJobId(query.jobId()));
    }

    @Transactional(readOnly = true)
    public TemporaryUrl prepareResultDownload(PackingJobResultQuery query) {
        ProjectAccess project = access.require(query.firebaseUid(), query.projectId(), ProjectPermission.READ);
        PackingJobId jobId = new PackingJobId(query.jobId());
        PackingJob job = requireJob(project.projectId(), jobId);
        if (job.status() != PackingJobStatus.SUCCEEDED) {
            throw new ResourceConflictException(
                                                "Packing job result is not available until the job succeeds");
        }
        artifacts.findResult(jobId)
                 .orElseThrow(() -> new ResourceNotFoundException(
                                                                  "Result artifact for packing job " + jobId + " was not found"));
        return artifacts.readUrl(new PackingArtifact.Result(jobId), DOWNLOAD_URL_VALIDITY, false);
    }

    @Transactional(readOnly = true)
    public TemporaryUrl prepareResultBinDownload(PackingJobResultBinQuery query) {
        ProjectAccess project = access.require(query.firebaseUid(), query.projectId(), ProjectPermission.READ);
        PackingJob job = requireJob(project.projectId(), new PackingJobId(query.jobId()));
        job.requireResultBin(query.number());
        return artifacts.readUrl(PackingArtifact.binResult(job, query.number()), DOWNLOAD_URL_VALIDITY, true);
    }

    @Transactional(readOnly = true)
    public TemporaryUrl preparePreviewDownload(PackingJobPreviewQuery query) {
        ProjectAccess project = access.require(query.firebaseUid(), query.projectId(), ProjectPermission.READ);
        PackingJob job = requireJob(project.projectId(), new PackingJobId(query.jobId()));
        PreviewModel model = job.requirePreview(query.quality());
        return artifacts.readUrl(new PackingArtifact.Preview(job.id(), model.quality()), DOWNLOAD_URL_VALIDITY, query.download());
    }

    @Transactional(readOnly = true)
    public TemporaryUrl prepareResultBinPreviewDownload(PackingJobResultBinPreviewQuery query) {
        ProjectAccess project = access.require(query.firebaseUid(), query.projectId(), ProjectPermission.READ);
        PackingJob job = requireJob(project.projectId(), new PackingJobId(query.jobId()));
        PreviewModel model = job.requireBinPreview(query.number(), query.quality());
        return artifacts.readUrl(PackingArtifact.binPreview(job, query.number(), model.quality()), DOWNLOAD_URL_VALIDITY, query.download());
    }

    @Transactional(readOnly = true)
    public PackingJobDraft draft(DraftQuery query) {
        ProjectAccess project = access.require(query.firebaseUid(), query.projectId(), ProjectPermission.READ);
        ProjectId projectId = project.projectId();
        if (query.fromJobId() != null) {
            PackingJobView selected = requireView(projectId, new PackingJobId(query.fromJobId()));
            if (selected.input() == null) {
                throw new ResourceConflictException("This job predates structured input and cannot be re-run");
            }
            return draftFrom(DraftSource.SELECTED_JOB, selected, projectId);
        }
        Optional<PackingJobView> own = finder.latestOwnInProject(projectId, project.userId());
        if (own.isPresent()) {
            return draftFrom(DraftSource.LAST_OWN_JOB, own.get(), projectId);
        }
        Optional<PackingJobView> latest = finder.latestInProject(projectId);
        if (latest.isPresent()) {
            return draftFrom(DraftSource.LAST_PROJECT_JOB, latest.get(), projectId);
        }
        return new PackingJobDraft(DraftSource.DEFAULTS, null, inputView(schema.defaults(), List.of()), List.of(), List.of());
    }

    private PackingJobDraft draftFrom(DraftSource source, PackingJobView job, ProjectId projectId) {
        PackingJobInputView stored = job.input();
        Set<ModelFormat> accepted = schema.acceptedFormats();
        List<PackingJobInputView.Item> kept = new ArrayList<>();
        List<OmittedItem> omitted = new ArrayList<>();
        for (PackingJobInputView.Item item : stored.items()) {
            Optional<StoredFile> file = availableFile(projectId, new FileId(item.fileId())).filter(candidate -> accepted.contains(candidate.format()));
            if (file.isPresent()) {
                kept.add(new PackingJobInputView.Item(item.fileId(),
                                                      file.get()
                                                          .name()
                                                          .value(),
                                                      item.copies()));
            } else {
                omitted.add(new OmittedItem(item.fileId(), item.fileName()));
            }
        }
        Reconciled settings = schema.reconcile(new Settings(stored.maxRuntimeSeconds(),
                                                            new PackingParameters(stored.parametersJson())));
        return new PackingJobDraft(source, job.id(), inputView(settings.settings(), kept), omitted, settings.resetFields());
    }

    private PackingJobInputView inputView(Settings settings, List<PackingJobInputView.Item> items) {
        return new PackingJobInputView(schema.document()
                                             .version(),
                                       settings.maxRuntimeSeconds(),
                                       settings.parameters()
                                               .json(),
                                       items);
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
                    .filter(file -> file.isAvailableIn(projectId));
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

    private PackingJob requireJob(ProjectId projectId, PackingJobId jobId) {
        return jobs.findById(jobId)
                   .filter(candidate -> candidate.projectId()
                                                 .equals(projectId))
                   .orElseThrow(() -> PackingJobNotFoundException.byId(jobId));
    }

    private PackingJobView requireView(ProjectId projectId, PackingJobId jobId) {
        return finder.detailInProject(projectId, jobId)
                     .orElseThrow(() -> PackingJobNotFoundException.byId(jobId));
    }

    public record CreatePackingJobCommand(String firebaseUid, UUID projectId, String body) {
    }

    public enum DraftSource {
        SELECTED_JOB,
        LAST_OWN_JOB,
        LAST_PROJECT_JOB,
        DEFAULTS
    }

    public record PackingJobDraft(DraftSource source,
            UUID sourceJobId,
            PackingJobInputView input,
            List<OmittedItem> omittedItems,
            List<String> resetFields) {

        public PackingJobDraft {
            omittedItems = List.copyOf(omittedItems);
            resetFields = List.copyOf(resetFields);
        }
    }

    public record OmittedItem(UUID fileId, String fileName) {
    }

    public record DraftQuery(String firebaseUid, UUID projectId, UUID fromJobId) {
    }

    public record ListPackingJobsQuery(String firebaseUid, UUID projectId, PackingJobListCriteria criteria) {
    }

    public record CancelPackingJobCommand(String firebaseUid, UUID projectId, UUID jobId) {
    }

    public record PackingJobQuery(String firebaseUid, UUID projectId, UUID jobId) {
    }

    public record PackingJobResultQuery(String firebaseUid, UUID projectId, UUID jobId) {
    }

    public record PackingJobResultBinQuery(String firebaseUid, UUID projectId, UUID jobId, int number) {
    }

    public record PackingJobResultBinPreviewQuery(String firebaseUid, UUID projectId, UUID jobId, int number, PreviewQuality quality,
            boolean download) {
    }

    public record PackingJobPreviewQuery(String firebaseUid, UUID projectId, UUID jobId, PreviewQuality quality, boolean download) {
    }
}
