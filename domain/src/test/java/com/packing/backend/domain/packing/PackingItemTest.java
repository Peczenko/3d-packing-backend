package com.packing.backend.domain.packing;

import com.packing.backend.domain.file.Checksum;
import com.packing.backend.domain.file.FileId;
import com.packing.backend.domain.file.FileName;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StoredFile;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.user.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackingItemTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

    @Test
    void snapshotsEveryFieldOfTheStoredFile() {
        StoredFile file = StoredFile.upload(FileId.generate(),
                                            UserId.generate(),
                                            ProjectId.generate(),
                                            new FileName("bunny.obj"),
                                            1_024,
                                            new Checksum("b".repeat(64)),
                                            NOW);

        PackingItem item = PackingItem.of(file, 3);

        assertThat(item).isEqualTo(new PackingItem(file.id(),
                                                   file.name(),
                                                   ModelFormat.OBJ,
                                                   file.checksum(),
                                                   file.storageKey(),
                                                   3));
    }

    @Test
    void acceptsOneToOneHundredCopies() {
        assertThat(PackingInputs.item("bunny.obj", 1)
                                .copies()).isEqualTo(1);
        assertThat(PackingInputs.item("bunny.obj", 100)
                                .copies()).isEqualTo(100);
    }

    @Test
    void rejectsCopiesOutsideOneToOneHundred() {
        for (int copies : new int[] { 0, 101 }) {
            assertThatThrownBy(() -> PackingInputs.item("bunny.obj", copies))
                                                                             .isInstanceOf(DomainRuleViolationException.class)
                                                                             .hasMessage("copies must be between 1 and 100");
        }
    }
}
