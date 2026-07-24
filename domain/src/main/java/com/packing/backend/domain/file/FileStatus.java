package com.packing.backend.domain.file;

/** {@code DELETED} is a tombstone, not a row removal, so a failed blob cleanup can be retried. */
public enum FileStatus {

    AVAILABLE,
    DELETED
}
