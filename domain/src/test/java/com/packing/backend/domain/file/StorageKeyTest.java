package com.packing.backend.domain.file;

import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageKeyTest {

    private static final FileId ID = new FileId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    @Test
    void derivesTheKeyFromTheIdAlone() {
        assertThat(StorageKey.forFile(ID).value())
                .isEqualTo("files/11111111-1111-1111-1111-111111111111");
    }

    @Test
    void isDeterministicSoARetriedUploadOverwritesItsOwnBlob() {
        assertThat(StorageKey.forFile(ID)).isEqualTo(StorageKey.forFile(ID));
    }

    @Test
    void rejectsAKeyOutsideTheFilesPrefix() {
        assertThatThrownBy(() -> new StorageKey("other/" + ID.value()))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining(StorageKey.PREFIX);
    }

    @Test
    void rejectsTraversal() {
        assertThatThrownBy(() -> new StorageKey("files/../secrets"))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("..");
    }

    @Test
    void rejectsBlankAndOversizedKeys() {
        assertThatThrownBy(() -> new StorageKey("  "))
                .isInstanceOf(DomainRuleViolationException.class);
        assertThatThrownBy(() -> new StorageKey(StorageKey.PREFIX + "a".repeat(StorageKey.MAX_LENGTH)))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("at most");
    }
}
