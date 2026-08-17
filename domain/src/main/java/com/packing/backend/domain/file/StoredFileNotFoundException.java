package com.packing.backend.domain.file;

import com.packing.backend.domain.shared.ResourceNotFoundException;

public class StoredFileNotFoundException extends ResourceNotFoundException {

    public StoredFileNotFoundException(String message) {
        super(message);
    }

    public static StoredFileNotFoundException byId(FileId id) {
        return new StoredFileNotFoundException("No file with id " + id);
    }
}
