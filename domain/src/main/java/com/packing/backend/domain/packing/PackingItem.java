package com.packing.backend.domain.packing;

import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StorageKey;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.shared.DomainRuleViolationException;

import java.util.Objects;

public record PackingItem(FileId fileId,
        FileName fileName,
        ModelFormat format,
        Checksum checksum,
        StorageKey storageKey,
        int copies) {

    public static final int MIN_COPIES = 1;
    public static final int MAX_COPIES = 100;

    public PackingItem {
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(storageKey, "storageKey");
        if (copies < MIN_COPIES || copies > MAX_COPIES) {
            throw new DomainRuleViolationException(
                                                   "copies must be between " + MIN_COPIES + " and " + MAX_COPIES);
        }
    }

    public static PackingItem of(StoredFile file, int copies) {
        return new PackingItem(file.id(), file.name(), file.format(), file.checksum(), file.storageKey(), copies);
    }
}
