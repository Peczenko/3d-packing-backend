package com.packing.backend.core.file;

import com.packing.backend.domain.shared.ResourceNotFoundException;

public class SampleModelNotFoundException extends ResourceNotFoundException {

    public SampleModelNotFoundException(String message) {
        super(message);
    }

    public static SampleModelNotFoundException byId(String id) {
        return new SampleModelNotFoundException("No sample model with id " + id);
    }
}
