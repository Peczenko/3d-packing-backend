package com.packing.backend.core.file.port.in;

import com.packing.backend.core.file.FileView;
import com.packing.backend.core.shared.Page;
import com.packing.backend.domain.shared.DomainRuleViolationException;

public interface ListFilesUseCase {

    Page<FileView> listFiles(ListFilesCommand command);

    /**
     * The authority for these bounds; the controller's {@code @Min}/{@code @Max} are only
     * a cheap first pass at the edge.
     */
    record ListFilesCommand(String firebaseUid, int page, int size) {

        public static final int DEFAULT_SIZE = 20;
        public static final int MAX_SIZE = 100;

        public ListFilesCommand {
            if (page < 0) {
                throw new DomainRuleViolationException("Page must not be negative");
            }
            if (size < 1 || size > MAX_SIZE) {
                throw new DomainRuleViolationException(
                        "Page size must be between 1 and " + MAX_SIZE);
            }
        }
    }
}
