package com.packing.backend.infra.storage;

import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.domain.file.event.FileDeleted;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
class BlobCleanupListener {

    private final BinaryStorage storage;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onFileDeleted(FileDeleted event) {
        try {
            storage.delete(event.storageKey());
        } catch (RuntimeException e) {
            log.error("File {} was deleted for owner {} but blob {} could not be removed. "
                    + "The file is already unreachable (the row is tombstoned); the "
                    + "orphaned blob needs reaping.",
                      event.fileId(),
                      event.ownerId(),
                      event.storageKey(),
                      e);
        }
    }
}
