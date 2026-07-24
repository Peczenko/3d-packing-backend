package com.packing.backend.domain.file;

import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelFormatTest {

    @ParameterizedTest
    @CsvSource({
            "stl, STL",
            "obj, OBJ",
            "step, STEP",
            "stp, STEP",
            "3mf, THREE_MF",
            "ply, PLY",
            "gltf, GLTF",
            "glb, GLB"
    })
    void resolvesEveryAcceptedExtension(String extension, ModelFormat expected) {
        assertThat(ModelFormat.fromExtension(extension)).isEqualTo(expected);
    }

    @Test
    void matchesExtensionsCaseInsensitively() {
        assertThat(ModelFormat.fromExtension("STL")).isEqualTo(ModelFormat.STL);
        assertThat(ModelFormat.fromExtension(" Stp ")).isEqualTo(ModelFormat.STEP);
    }

    @Test
    void rejectsAnUnsupportedExtensionAndListsWhatIsAccepted() {
        assertThatThrownBy(() -> ModelFormat.fromExtension("exe"))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("exe")
                .hasMessageContaining("stl");
    }

    @Test
    void rejectsABlankExtension() {
        assertThatThrownBy(() -> ModelFormat.fromExtension(" "))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void servesACanonicalContentTypePerFormat() {
        assertThat(ModelFormat.STL.contentType()).isEqualTo("model/stl");
        assertThat(ModelFormat.GLB.contentType()).isEqualTo("model/gltf-binary");
    }

    @Test
    void exposesEveryExtensionForClientHints() {
        assertThat(ModelFormat.allExtensions())
                .contains("stl", "obj", "step", "stp", "3mf", "ply", "gltf", "glb");
    }
}
