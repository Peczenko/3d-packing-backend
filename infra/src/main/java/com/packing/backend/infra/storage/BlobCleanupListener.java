package com.packing.backend.infra.storage;

import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.domain.file.event.FileDeleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs {@link TransactionPhase#AFTER_COMMIT}: PostgreSQL and the object store cannot share
 * a transaction, and unlike a database row, a deleted blob cannot be rolled back.
 *
 * <p>Failures are logged, never rethrown — the row already says {@code DELETED}, so the
 * file is unreachable through the API regardless; an {@code ERROR} here means a blob needs
 * reaping, not that the deletion failed.
 */
@Component
class BlobCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(BlobCleanupListener.class);

    private final BinaryStorage storage;

    BlobCleanupListener(BinaryStorage storage) {
        this.storage = storage;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onFileDeleted(FileDeleted event) {
        try {
            storage.delete(event.storageKey());
        } catch (RuntimeException e) {
            log.error("File {} was deleted for owner {} but blob {} could not be removed. "
                            + "The file is already unreachable (the row is tombstoned); the "
                            + "orphaned blob needs reaping.",
                    event.fileId(), event.ownerId(), event.storageKey(), e);
        }
    }
}
