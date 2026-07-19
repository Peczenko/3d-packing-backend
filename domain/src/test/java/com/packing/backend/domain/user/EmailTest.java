package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTest {

    @Test
    void isTrimmedAndLowerCased() {
        assertThat(new Email("  Ada@Example.COM ").value()).isEqualTo("ada@example.com");
    }

    @Test
    void normalisationMakesEqualityCaseInsensitive() {
        assertThat(new Email("ADA@example.com")).isEqualTo(new Email("ada@example.com"));
    }

    @Test
    void exposesTheLocalPartForUsernameDerivation() {
        assertThat(new Email("ada.lovelace@example.com").localPart()).isEqualTo("ada.lovelace");
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-at-sign", "@example.com", "ada@", "ada@localhost", "ada @example.com"})
    void rejectsMalformedAddresses(String value) {
        assertThatThrownBy(() -> new Email(value))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void rejectsBlankValues() {
        assertThatThrownBy(() -> new Email("   "))
                .isInstanceOf(DomainRuleViolationException.class);
    }
}
