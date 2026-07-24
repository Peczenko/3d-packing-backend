package com.packing.backend.domain.file;

import com.packing.backend.domain.shared.ResourceNotFoundException;

/**
 * Not existing, already deleted, and belonging to somebody else all collapse into this one
 * 404: a 403 for another user's file would confirm the id exists, turning the endpoint into
 * an enumeration oracle.
 */
public class StoredFileNotFoundException extends ResourceNotFoundException {

    public StoredFileNotFoundException(String message) {
        super(message);
    }

    public static StoredFileNotFoundException byId(FileId id) {
        return new StoredFileNotFoundException("No file with id " + id);
    }
}
