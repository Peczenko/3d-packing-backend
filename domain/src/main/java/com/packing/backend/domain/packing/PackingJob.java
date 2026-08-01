package com.packing.backend.domain.packing;

import com.packing.backend.domain.packing.event.PackingJobQueued;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.shared.AggregateRoot;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.UserId;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public final class PackingJob extends AggregateRoot {

    public static final long INITIAL_VERSION = 0L;
    public static final long MIN_RUNTIME_SECONDS = 1L;
    public static final long MAX_RUNTIME_SECONDS = 7_200L;

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-fA-F]{64}$");

    @EqualsAndHashCode.Include
    private final PackingJobId id;

    private final ProjectId projectId;
    private final UserId requestedBy;
    private final String specJson;
    private final long maxRuntimeSeconds;
    private final Instant createdAt;

    private PackingJobStatus status;
    private String engineVersion;
    private String engineChecksum;
    private Instant dispatchedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private String resultFileName;
    private String resultContentType;
    private String resultChecksum;
    private Long resultSizeBytes;
    private String failureReason;
    private long version;

    private PackingJob(PackingJobId id,
                       ProjectId projectId,
                       UserId requestedBy,
                       String specJson,
                       long maxRuntimeSeconds,
                       PackingJobStatus status,
                       String engineVersion,
                       String engineChecksum,
                       Instant dispatchedAt,
                       Instant startedAt,
                       Instant finishedAt,
                       String resultFileName,
                       String resultContentType,
                       String resultChecksum,
                       Long resultSizeBytes,
                       String failureReason,
                       long version,
                       Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.specJson = requireText(specJson, "specJson");
        requireRuntime(maxRuntimeSeconds);
        this.maxRuntimeSeconds = maxRuntimeSeconds;
        this.status = Objects.requireNonNull(status, "status");
        this.engineVersion = engineVersion;
        this.engineChecksum = engineChecksum;
        this.dispatchedAt = dispatchedAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.resultFileName = resultFileName;
        this.resultContentType = resultContentType;
        this.resultChecksum = resultChecksum;
        this.resultSizeBytes = resultSizeBytes;
        this.failureReason = failureReason;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static PackingJob queue(PackingJobId id,
                                   ProjectId projectId,
                                   UserId requestedBy,
                                   String specJson,
                                   long maxRuntimeSeconds,
                                   Instant now) {
        PackingJob job = new PackingJob(id, projectId, requestedBy, specJson, maxRuntimeSeconds,
                PackingJobStatus.QUEUED, null, null, null, null, null,
                null, null, null, null, null, INITIAL_VERSION, now);
        job.recordEvent(new PackingJobQueued(id, projectId, now));
        return job;
    }

    public static PackingJob rehydrate(PackingJobId id,
                                       ProjectId projectId,
                                       UserId requestedBy,
                                       String specJson,
                                       long maxRuntimeSeconds,
                                       PackingJobStatus status,
                                       String engineVersion,
                                       String engineChecksum,
                                       Instant dispatchedAt,
                                       Instant startedAt,
                                       Instant finishedAt,
                                       String resultFileName,
                                       String resultContentType,
                                       String resultChecksum,
                                       Long resultSizeBytes,
                                       String failureReason,
                                       long version,
                                       Instant createdAt) {
        return new PackingJob(id, projectId, requestedBy, specJson, maxRuntimeSeconds, status,
                engineVersion, engineChecksum, dispatchedAt, startedAt, finishedAt,
                resultFileName, resultContentType, resultChecksum, resultSizeBytes, failureReason,
                version, createdAt);
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
        if (status == PackingJobStatus.RUNNING) {
            return false;
        }
        requireStatus(PackingJobStatus.QUEUED);
        engineVersion = requireText(version, "engineVersion");
        engineChecksum = requireSha256(checksum, "engineChecksum");
        status = PackingJobStatus.RUNNING;
        startedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    public boolean succeed(String fileName,
                           String contentType,
                           long sizeBytes,
                           String checksum,
                           String version,
                           String engineChecksum,
                           Instant now) {
        if (status == PackingJobStatus.SUCCEEDED) {
            if (hasSameSuccess(fileName, contentType, sizeBytes, checksum, version,
                    engineChecksum, now)) {
                return false;
            }
            throw new DomainRuleViolationException("Cannot succeed packing job " + id + " from " + status);
        }
        requireStatus(PackingJobStatus.RUNNING);

        String requiredFileName = requireText(fileName, "resultFileName");
        String requiredContentType = requireText(contentType, "resultContentType");
        requirePositiveSize(sizeBytes);
        String requiredChecksum = requireSha256(checksum, "resultChecksum");
        String requiredVersion = requireText(version, "engineVersion");
        String requiredEngineChecksum = requireSha256(engineChecksum, "engineChecksum");
        Instant finished = Objects.requireNonNull(now, "now");

        resultFileName = requiredFileName;
        resultContentType = requiredContentType;
        resultSizeBytes = sizeBytes;
        resultChecksum = requiredChecksum;
        this.engineVersion = requiredVersion;
        this.engineChecksum = requiredEngineChecksum;
        status = PackingJobStatus.SUCCEEDED;
        finishedAt = finished;
        return true;
    }

    public boolean fail(String reason, String version, String checksum, Instant now) {
        if (status == PackingJobStatus.FAILED) {
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
        return true;
    }

    public boolean failBeforeStart(String reason, Instant now) {
        String requiredReason = requireText(reason, "failureReason");
        if (status == PackingJobStatus.FAILED) {
            if (engineVersion == null && engineChecksum == null && failureReason.equals(requiredReason)) {
                return false;
            }
            throw new DomainRuleViolationException("Cannot fail packing job " + id + " from " + status);
        }
        requireStatus(PackingJobStatus.QUEUED);
        failureReason = requiredReason;
        status = PackingJobStatus.FAILED;
        finishedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    public void markPersisted() {
        version++;
    }

    private boolean hasSameSuccess(String fileName,
                                   String contentType,
                                   long sizeBytes,
                                   String checksum,
                                   String version,
                                   String engineChecksum,
                                   Instant now) {
        return Objects.equals(resultFileName, fileName)
                && Objects.equals(resultContentType, contentType)
                && Objects.equals(resultSizeBytes, sizeBytes)
                && Objects.equals(resultChecksum, checksum)
                && Objects.equals(this.engineVersion, version)
                && Objects.equals(this.engineChecksum, engineChecksum)
                && Objects.equals(finishedAt, now);
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(field + " must not be blank");
        }
        return value;
    }

    private static String requireSha256(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new DomainRuleViolationException(field + " must be a SHA-256 hexadecimal string");
        }
        return value;
    }

    @Override
    public String toString() {
        return "PackingJob[id=" + id + ", projectId=" + projectId + ", status=" + status + "]";
    }
}
