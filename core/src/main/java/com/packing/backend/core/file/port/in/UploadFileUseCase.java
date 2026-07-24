package com.packing.backend.core.file.port.in;

import com.packing.backend.core.file.FileView;
import com.packing.backend.core.shared.ContentSource;

public interface UploadFileUseCase {

    FileView upload(UploadFileCommand command);

    /**
     * @param declaredSizeBytes what the client said it was sending. Advisory only — the
     *                          stored size is counted from the bytes actually received,
     *                          because this value is caller-controlled.
     */
    record UploadFileCommand(String firebaseUid,
                             String originalFilename,
                             long declaredSizeBytes,
                             ContentSource content) {
    }
}
