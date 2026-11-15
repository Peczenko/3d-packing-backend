package com.packing.backend.infra.storage;

import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.file.event.FileDeleted;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BlobCleanupListenerTest {

    private final BinaryStorage storage = mock(BinaryStorage.class);

    @Test
    void deletesTheOriginalAndTheConvertedObj() {
        FileId id = FileId.generate();

        new BlobCleanupListener(storage).onFileDeleted(event(id));

        verify(storage).delete(StorageKey.forFile(id));
        verify(storage).delete(StorageKey.forConvertedObj(id));
    }

    @Test
    void aBlobThatCannotBeDeletedDoesNotKeepTheOtherAlive() {
        FileId id = FileId.generate();
        doThrow(new IllegalStateException("storage down")).when(storage)
                                                          .delete(StorageKey.forFile(id));

        new BlobCleanupListener(storage).onFileDeleted(event(id));

        verify(storage).delete(StorageKey.forConvertedObj(id));
    }

    private static FileDeleted event(FileId id) {
        return new FileDeleted(id, List.of(StorageKey.forFile(id), StorageKey.forConvertedObj(id)), UserId.generate(), Instant.now());
    }
}
