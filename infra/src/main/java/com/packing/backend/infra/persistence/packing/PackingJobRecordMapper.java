package com.packing.backend.infra.persistence.packing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.packing.PackingInput;
import com.packing.backend.domain.packing.PackingItem;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingParameters;
import com.packing.backend.infra.persistence.jooq.tables.records.PackingJobsRecord;
import com.packing.backend.infra.persistence.shared.AggregateTable;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.packing.backend.infra.persistence.jooq.tables.PackingJobs.PACKING_JOBS;

final class PackingJobRecordMapper {

    static final AggregateTable<PackingJobsRecord> TABLE = new AggregateTable<>(
                                                                                "PackingJob",
                                                                                PACKING_JOBS.VERSION,
                                                                                Set.<Field<?>>of(
                                                                                                 PACKING_JOBS.PROJECT_ID,
                                                                                                 PACKING_JOBS.CREATED_BY,
                                                                                                 PACKING_JOBS.SPEC_JSON,
                                                                                                 PACKING_JOBS.INPUT_SCHEMA_VERSION,
                                                                                                 PACKING_JOBS.ITEMS,
                                                                                                 PACKING_JOBS.PARAMETERS,
                                                                                                 PACKING_JOBS.MAX_RUNTIME_SECONDS,
                                                                                                 PACKING_JOBS.CREATED_AT));

    private static final ObjectMapper                  JSON      = new ObjectMapper();
    private static final TypeReference<List<ItemJson>> ITEM_LIST = new TypeReference<>() {
                                                                 };

    private PackingJobRecordMapper() {
    }

    static PackingJob toDomain(PackingJobsRecord record) {
        return PackingJob.rehydrate(
                                    record.getId(),
                                    record.getProjectId(),
                                    record.getCreatedBy(),
                                    inputOf(record),
                                    record.getMaxRuntimeSeconds(),
                                    record.getStatus(),
                                    record.getEngineVersion(),
                                    record.getEngineChecksumSha256(),
                                    record.getDispatchedAt(),
                                    record.getStartedAt(),
                                    record.getFinishedAt(),
                                    record.getResultFileName(),
                                    record.getResultContentType(),
                                    record.getResultChecksumSha256(),
                                    record.getResultSizeBytes(),
                                    record.getFailureReason(),
                                    record.getVersion(),
                                    record.getCreatedAt());
    }

    static PackingJobsRecord toRecord(PackingJob job) {
        PackingInput input = job.input();
        PackingJobsRecord record = new PackingJobsRecord();
        record.setId(job.id());
        record.setProjectId(job.projectId());
        record.setCreatedBy(job.requestedBy());
        record.setInputSchemaVersion(input.schemaVersion());
        record.setItems(itemsToJson(input.items()));
        record.setParameters(JSONB.valueOf(input.parameters()
                                                .json()));
        record.setMaxRuntimeSeconds(job.maxRuntimeSeconds());
        record.setStatus(job.status());
        record.setEngineVersion(job.engineVersion());
        record.setEngineChecksumSha256(job.engineChecksum());
        record.setDispatchedAt(job.dispatchedAt());
        record.setStartedAt(job.startedAt());
        record.setFinishedAt(job.finishedAt());
        record.setFailureReason(job.failureReason());
        record.setResultFileName(job.resultFileName());
        record.setResultContentType(job.resultContentType());
        record.setResultSizeBytes(job.resultSizeBytes());
        record.setResultChecksumSha256(job.resultChecksum());
        record.setVersion(job.version());
        record.setCreatedAt(job.createdAt());
        return record;
    }

    static PackingInput inputOf(Record record) {
        JSONB items = record.get(PACKING_JOBS.ITEMS);
        if (items == null) {
            return null;
        }
        return new PackingInput(record.get(PACKING_JOBS.INPUT_SCHEMA_VERSION),
                                itemsFromJson(items),
                                new PackingParameters(compact(record.get(PACKING_JOBS.PARAMETERS))));
    }

    private static JSONB itemsToJson(List<PackingItem> items) {
        List<ItemJson> json = items.stream()
                                   .map(item -> new ItemJson(item.fileId()
                                                                 .value(),
                                                             item.fileName()
                                                                 .value(),
                                                             item.format()
                                                                 .name(),
                                                             item.checksum()
                                                                 .value(),
                                                             item.storageKey()
                                                                 .value(),
                                                             item.copies()))
                                   .toList();
        try {
            return JSONB.valueOf(JSON.writeValueAsString(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode packing items", e);
        }
    }

    private static List<PackingItem> itemsFromJson(JSONB items) {
        try {
            return JSON.readValue(items.data(), ITEM_LIST)
                       .stream()
                       .map(item -> new PackingItem(new FileId(item.fileId()),
                                                    new FileName(item.fileName()),
                                                    ModelFormat.valueOf(item.format()),
                                                    new Checksum(item.checksum()),
                                                    new StorageKey(item.storageKey()),
                                                    item.copies()))
                       .toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored packing items are unreadable", e);
        }
    }

    private static String compact(JSONB json) {
        try {
            return JSON.readTree(json.data())
                       .toString();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored packing parameters are unreadable", e);
        }
    }

    record ItemJson(UUID fileId, String fileName, String format, String checksum, String storageKey, int copies) {
    }
}
