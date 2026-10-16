package com.packing.backend.core.packing;

import com.packing.backend.domain.shared.ResourceConflictException;

public class PackingInputSchemaChangedException extends ResourceConflictException {

    public PackingInputSchemaChangedException() {
        super("Input schema changed; reload the form");
    }
}
