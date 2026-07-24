package com.packing.backend.core.file.port.in;

import java.util.UUID;

public interface DeleteFileUseCase {

    void deleteFile(DeleteFileCommand command);

    record DeleteFileCommand(String firebaseUid, UUID fileId) {
    }
}
