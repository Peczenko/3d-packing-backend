package com.packing.backend.core.packing.port.out;

import com.packing.backend.domain.packing.PackingJob;
import com.packing.backend.domain.packing.PackingJobId;

import java.util.Optional;

public interface PackingJobRepository {

    PackingJob save(PackingJob job);

    Optional<PackingJob> findById(PackingJobId id);
}
