package com.packing.backend.infra.persistence.packing;

import com.packing.backend.core.packing.port.out.PackingJobRepository;
import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.infra.persistence.shared.AggregateWriter;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.packing.backend.infra.persistence.jooq.tables.PackingJobs.PACKING_JOBS;

@Repository
@RequiredArgsConstructor
public class JooqPackingJobRepository implements PackingJobRepository {

    private final DSLContext      dsl;
    private final AggregateWriter writer;

    @Override
    public PackingJob save(PackingJob job) {
        writer.upsert(PackingJobRecordMapper.TABLE,
                      PackingJobRecordMapper.toRecord(job),
                      job.version());
        job.markPersisted();
        return job;
    }

    @Override
    public Optional<PackingJob> findById(PackingJobId id) {
        return dsl.selectFrom(PACKING_JOBS)
                  .where(PACKING_JOBS.ID.eq(id))
                  .fetchOptional()
                  .map(PackingJobRecordMapper::toDomain);
    }
}
