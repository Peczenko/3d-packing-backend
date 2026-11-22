package com.packing.backend.domain.packing;

import com.packing.backend.domain.packing.event.PackingJobCancelled;
import com.packing.backend.domain.packing.event.PackingJobFinished;
import com.packing.backend.domain.packing.event.PackingJobQueued;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.shared.AggregateRoot;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.shared.ResourceNotFoundException;
import com.packing.backend.domain.user.UserId;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public final class PackingJob extends AggregateRoot {

    public static final long INITIAL_VERSION     = 0L;
    public static final long MIN_RUNTIME_SECONDS = 1L;
    public static final long MAX_RUNTIME_SECONDS = 7_200L;

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-fA-F]{64}$");

    @EqualsAndHashCode.Include
    private final PackingJobId id;

    private final ProjectId    projectId;
    private final UserId       requestedBy;
    private final PackingInput input;
    private final long         maxRuntimeSeconds;
    private final Instant      createdAt;

    private PackingJobStatus status;
    private String           engineVersion;
    private String           engineChecksum;
    private Instant          dispatchedAt;
    private Instant          startedAt;
    private Instant          finishedAt;
    private int              binCount;
    private ResultStatus     resultStatus;
    private String           resultFileName;
    private String           resultContentType;
    private String           resultChecksum;
    private Long             resultSizeBytes;
    private List<ResultBin>  resultBins;
    private String           failureReason;
    private PackingPreview   preview;
    private long             version;

    private PackingJob(PackingJobId id,
                       ProjectId projectId,
                       UserId requestedBy,
                       PackingInput input,
                       long maxRuntimeSeconds,
                       PackingJobStatus status,
                       String engineVersion,
                       String engineChecksum,
                       Instant dispatchedAt,
                       Instant startedAt,
                       Instant finishedAt,
                       int binCount,
                       ResultStatus resultStatus,
                       String resultFileName,
                       String resultContentType,
                       String resultChecksum,
                       Long resultSizeBytes,
                       List<ResultBin> resultBins,
                       String failureReason,
                       PackingPreview preview,
                       long version,
                       Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.input = input;
        requireRuntime(maxRuntimeSeconds);
        this.maxRuntimeSeconds = maxRuntimeSeconds;
        this.status = Objects.requireNonNull(status, "status");
        this.engineVersion = engineVersion;
        this.engineChecksum = engineChecksum;
        this.dispatchedAt = dispatchedAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        if (binCount < 0) {
            throw new DomainRuleViolationException("binCount must not be negative");
        }
        this.binCount = binCount;
        this.resultStatus = Objects.requireNonNull(resultStatus, "resultStatus");
        this.resultFileName = resultFileName;
        this.resultContentType = resultContentType;
        this.resultChecksum = resultChecksum;
        this.resultSizeBytes = resultSizeBytes;
        this.resultBins = List.copyOf(Objects.requireNonNull(resultBins, "resultBins"));
        this.failureReason = failureReason;
        this.preview = Objects.requireNonNull(preview, "preview");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static PackingJob queue(PackingJobId id,
                                   ProjectId projectId,
                                   UserId requestedBy,
                                   PackingInput input,
                                   long maxRuntimeSeconds,
                                   Instant now) {
        PackingJob job = new PackingJob(id,
                                        projectId,
                                        requestedBy,
                                        Objects.requireNonNull(input, "input"),
                                        maxRuntimeSeconds,
                                        PackingJobStatus.QUEUED,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        0,
                                        ResultStatus.NONE,
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        PackingPreview.NONE,
                                        INITIAL_VERSION,
                                        now);
        job.recordEvent(new PackingJobQueued(id, projectId, now));
        return job;
    }

    public static PackingJob rehydrate(PackingJobId id,
                                       ProjectId projectId,
                                       UserId requestedBy,
                                       PackingInput input,
                                       long maxRuntimeSeconds,
                                       PackingJobStatus status,
                                       String engineVersion,
                                       String engineChecksum,
                                       Instant dispatchedAt,
                                       Instant startedAt,
                                       Instant finishedAt,
                                       int binCount,
                                       ResultStatus resultStatus,
                                       String resultFileName,
                                       String resultContentType,
                                       String resultChecksum,
                                       Long resultSizeBytes,
                                       List<ResultBin> resultBins,
                                       String failureReason,
                                       PackingPreview preview,
                                       long version,
                                       Instant createdAt) {
        return new PackingJob(id,
                              projectId,
                              requestedBy,
                              input,
                              maxRuntimeSeconds,
                              status,
                              engineVersion,
                              engineChecksum,
                              dispatchedAt,
                              startedAt,
                              finishedAt,
                              binCount,
                              resultStatus,
                              resultFileName,
                              resultContentType,
                              resultChecksum,
                              resultSizeBytes,
                              resultBins,
                              failureReason,
                              preview,
                              version,
                              createdAt);
    }

    public boolean markDispatched(Instant now) {
        if (dispatchedAt != null) {
            return false;
        }
        requireStatus(PackingJobStatus.QUEUED);
        dispatchedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    public boolean markRunning(String version, String checksum, Instant now) {
        if (status == PackingJobStatus.RUNNING || isTerminal()) {
            return false;
        }
        requireStatus(PackingJobStatus.QUEUED);
        engineVersion = requireText(version, "engineVersion");
        engineChecksum = requireSha256(checksum, "engineChecksum");
        status = PackingJobStatus.RUNNING;
        startedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    public boolean succeed(String version, String engineChecksum, int containers, Instant now) {
        if (isTerminal()) {
            return false;
        }

        String requiredVersion = requireText(version, "engineVersion");
        String requiredEngineChecksum = requireSha256(engineChecksum, "engineChecksum");
        if (containers < 1) {
            throw new DomainRuleViolationException("A packing job that succeeded fills at least one container");
        }
        Instant finished = Objects.requireNonNull(now, "now");

        this.engineVersion = requiredVersion;
        this.engineChecksum = requiredEngineChecksum;
        binCount = containers;
        status = PackingJobStatus.SUCCEEDED;
        finishedAt = finished;
        resultStatus = ResultStatus.PENDING;
        preview = PackingPreview.PENDING;
        recordFinished(finished);
        return true;
    }

    public boolean completeResult(String fileName, String contentType, long sizeBytes, String checksum, List<ResultBin> bins) {
        if (resultStatus != ResultStatus.PENDING) {
            return false;
        }

        String requiredFileName = requireText(fileName, "resultFileName");
        String requiredContentType = requireText(contentType, "resultContentType");
        requirePositiveSize(sizeBytes);
        String requiredChecksum = requireSha256(checksum, "resultChecksum");
        List<ResultBin> requiredBins = requireBins(bins, sizeBytes, requiredChecksum);
        if (requiredBins.size() != binCount) {
            throw new DomainRuleViolationException("A result in " + binCount + " containers needs " + binCount
                    + " container files, not " + requiredBins.size());
        }

        resultFileName = requiredFileName;
        resultContentType = requiredContentType;
        resultSizeBytes = sizeBytes;
        resultChecksum = requiredChecksum;
        resultBins = requiredBins;
        resultStatus = ResultStatus.READY;
        return true;
    }

    public boolean failResult() {
        if (resultStatus != ResultStatus.PENDING) {
            return false;
        }
        resultStatus = ResultStatus.FAILED;
        return true;
    }

    public boolean fail(String reason, String version, String checksum, Instant now) {
        if (isTerminal()) {
            return false;
        }
        if (status != PackingJobStatus.QUEUED && status != PackingJobStatus.RUNNING) {
            throw new DomainRuleViolationException("Cannot fail packing job " + id + " from " + status);
        }
        failureReason = requireText(reason, "failureReason");
        engineVersion = requireText(version, "engineVersion");
        engineChecksum = requireSha256(checksum, "engineChecksum");
        status = PackingJobStatus.FAILED;
        finishedAt = Objects.requireNonNull(now, "now");
        recordFinished(finishedAt);
        return true;
    }

    public boolean failStalled(String reason, Instant now) {
        if (isTerminal()) {
            return false;
        }
        String requiredReason = requireText(reason, "failureReason");
        Instant finished = Objects.requireNonNull(now, "now");
        failureReason = requiredReason;
        status = PackingJobStatus.FAILED;
        finishedAt = finished;
        recordFinished(finished);
        return true;
    }

    public void requireCancellable() {
        if (status == PackingJobStatus.SUCCEEDED || status == PackingJobStatus.FAILED) {
            throw new ResourceConflictException("Packing job " + id + " has already finished");
        }
    }

    public boolean cancel(Instant now) {
        if (isTerminal()) {
            return false;
        }
        status = PackingJobStatus.CANCELLED;
        finishedAt = Objects.requireNonNull(now, "now");
        recordEvent(new PackingJobCancelled(id, projectId, finishedAt));
        return true;
    }

    public boolean completePreview(List<PreviewModel> models, List<List<PreviewModel>> bins) {
        if (preview.status() != PreviewStatus.PENDING) {
            return false;
        }
        int expected = binCount > 1 ? binCount : 0;
        if (bins.size() != expected) {
            throw new DomainRuleViolationException("A result in " + binCount + " containers needs " + expected
                    + " container previews, not " + bins.size());
        }
        preview = PackingPreview.ready(models, bins);
        return true;
    }

    public boolean failPreview() {
        if (preview.status() != PreviewStatus.PENDING) {
            return false;
        }
        preview = PackingPreview.FAILED;
        return true;
    }

    public PreviewModel requirePreview(PreviewQuality quality) {
        requirePreviewReady();
        return preview.model(quality)
                      .orElseThrow(() -> new ResourceNotFoundException("Packing job " + id + " has no " + quality + " preview"));
    }

    public PreviewModel requireBinPreview(int number, PreviewQuality quality) {
        requireSucceeded();
        requireContainer(number, binCount);
        requirePreviewReady();
        return preview.binModel(number, binCount, quality)
                      .orElseThrow(() -> new ResourceNotFoundException("Packing job " + id + " has no " + quality
                              + " preview of result bin " + number));
    }

    public void requireResult() {
        requireSucceeded();
        if (resultStatus == ResultStatus.PENDING) {
            throw new ResourceConflictException("Packing job " + id + " is still writing its result");
        }
        if (resultStatus != ResultStatus.READY) {
            throw new ResourceConflictException("Packing job " + id + " has no result because it could not be written");
        }
    }

    public ResultBin requireResultBin(int number) {
        requireResult();
        requireContainer(number, resultBins.size());
        return resultBins.get(number - 1);
    }

    public void markPersisted() {
        version++;
    }

    public boolean isLegacy() {
        return input == null;
    }

    private void recordFinished(Instant now) {
        recordEvent(new PackingJobFinished(id,
                                           projectId,
                                           requestedBy,
                                           status,
                                           failureReason,
                                           startedAt,
                                           now));
    }

    private void requireSucceeded() {
        if (status != PackingJobStatus.SUCCEEDED) {
            throw new ResourceConflictException("Packing job result is not available until the job succeeds");
        }
    }

    private void requireContainer(int number, int containers) {
        if (number < 1 || number > containers) {
            throw new ResourceNotFoundException("Packing job " + id + " has no result bin " + number);
        }
    }

    private void requirePreviewReady() {
        if (preview.status() != PreviewStatus.READY) {
            throw new ResourceConflictException("Packing job " + id + " has no preview ready");
        }
    }

    private boolean isTerminal() {
        return status == PackingJobStatus.SUCCEEDED
                || status == PackingJobStatus.FAILED
                || status == PackingJobStatus.CANCELLED;
    }

    private void requireStatus(PackingJobStatus expected) {
        if (status != expected) {
            throw new DomainRuleViolationException(
                                                   "Cannot transition packing job " + id + " from " + status + " to " + expected);
        }
    }

    private static void requireRuntime(long seconds) {
        if (seconds < MIN_RUNTIME_SECONDS || seconds > MAX_RUNTIME_SECONDS) {
            throw new DomainRuleViolationException(
                                                   "maxRuntimeSeconds must be between " + MIN_RUNTIME_SECONDS + " and "
                                                           + MAX_RUNTIME_SECONDS);
        }
    }

    private static void requirePositiveSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new DomainRuleViolationException("resultSizeBytes must be positive");
        }
    }

    private static List<ResultBin> requireBins(List<ResultBin> bins, long sizeBytes, String checksum) {
        List<ResultBin> copied = List.copyOf(Objects.requireNonNull(bins, "bins"));
        if (copied.size() == 1 && !copied.getFirst()
                                         .equals(new ResultBin(sizeBytes, checksum))) {
            throw new DomainRuleViolationException("A single result bin must be the whole result");
        }
        return copied;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(field + " must not be blank");
        }
        return value;
    }

    private static String requireSha256(String value, String field) {
        if (value == null || !SHA_256.matcher(value)
                                     .matches()) {
            throw new DomainRuleViolationException(field + " must be a SHA-256 hexadecimal string");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "PackingJob[id=" + id + ", projectId=" + projectId + ", status=" + status + "]";
    }
}
