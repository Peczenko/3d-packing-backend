package com.packing.backend.domain.project;

import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectNameTest {

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(new ProjectName("  Chassis  ").value()).isEqualTo("Chassis");
    }

    @Test
    void keepsCaseAndSpacing() {
        assertThat(new ProjectName("Chassis Packing v2").value()).isEqualTo("Chassis Packing v2");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "\t" })
    void rejectsBlankNames(String value) {
        assertThatThrownBy(() -> new ProjectName(value))
                                                        .isInstanceOf(DomainRuleViolationException.class)
                                                        .hasMessageContaining("blank");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new ProjectName(null))
                                                       .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void acceptsExactlyTheMaximumLength() {
        String longest = "x".repeat(ProjectName.MAX_LENGTH);

        assertThat(new ProjectName(longest).value()).hasSize(ProjectName.MAX_LENGTH);
    }

    @Test
    void rejectsOneCharacterOverTheMaximum() {
        String tooLong = "x".repeat(ProjectName.MAX_LENGTH + 1);

        assertThatThrownBy(() -> new ProjectName(tooLong))
                                                          .isInstanceOf(DomainRuleViolationException.class)
                                                          .hasMessageContaining("at most");
    }

    @ParameterizedTest
    @ValueSource(chars = { 0x00, 0x07, 0x1F, 0x7F })
    void rejectsControlCharacters(char control) {
        String name = "Chassis" + control + "packing";

        assertThatThrownBy(() -> new ProjectName(name))
                                                       .isInstanceOf(DomainRuleViolationException.class)
                                                       .hasMessageContaining("control characters");
    }

    @Test
    void normalisesToComposedForm() {
        String composed = "Ch" + (char) 0x00E1 + "ssis";
        String decomposed = "Cha" + (char) 0x0301 + "ssis";

        assertThat(new ProjectName(decomposed).value()).isEqualTo(composed)
                                                       .hasSize(7);
    }
}
