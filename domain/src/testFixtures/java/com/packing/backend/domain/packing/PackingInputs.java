package com.packing.backend.domain.packing;

import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.StorageKey;

import java.util.List;

public final class PackingInputs {

    public static final PackingParameters NO_PARAMETERS = new PackingParameters("{}");

    private PackingInputs() {
    }

    public static PackingItem item(String fileName, int copies) {
        FileId id = FileId.generate();
        FileName name = new FileName(fileName);
        return new PackingItem(id,
                               name,
                               name.format(),
                               new Checksum("a".repeat(Checksum.HEX_LENGTH)),
                               StorageKey.forFile(id),
                               copies);
    }

    public static PackingInput of(PackingItem... items) {
        return new PackingInput(1, List.of(items), NO_PARAMETERS);
    }

    public static PackingInput single() {
        return of(item("bunny.obj", 1));
    }
}
