package com.packing.backend.domain.packing;

import com.packing.backend.domain.shared.ResourceNotFoundException;

public class PackingJobNotFoundException extends ResourceNotFoundException {

    private PackingJobNotFoundException(String message) {
        super(message);
    }

    public static PackingJobNotFoundException byId(PackingJobId id) {
        return new PackingJobNotFoundException("No packing job found with id " + id);
    }
}
