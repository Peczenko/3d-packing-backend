package com.packing.backend.domain.user;

import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsernameTest {

    @Test
    void isLowerCased() {
        assertThat(new Username("AdaLovelace").value()).isEqualTo("adalovelace");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "-leading", ".leading", "has space", "has@symbol", "hasümlaut"})
    void rejectsMalformedValues(String value) {
        assertThatThrownBy(() -> new Username(value))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void rejectsValuesLongerThanTheLimit() {
        assertThatThrownBy(() -> new Username("a".repeat(Username.MAX_LENGTH + 1)))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void suggestionStripsIllegalCharacters() {
        assertThat(Username.suggestionFrom("Ada Lovelace!").value()).isEqualTo("adalovelace");
    }

    @Test
    void suggestionStripsLeadingPunctuationBecauseTheFirstCharacterIsStricter() {
        assertThat(Username.suggestionFrom("...ada").value()).isEqualTo("ada");
    }

    @Test
    void suggestionPadsInputShorterThanTheMinimum() {
        assertThat(Username.suggestionFrom("jo").value()).isEqualTo("jo0");
    }

    @Test
    void suggestionTruncatesInputLongerThanTheMaximum() {
        assertThat(Username.suggestionFrom("a".repeat(200)).value())
                .hasSize(Username.MAX_LENGTH);
    }

    @Test
    void suggestionFailsWhenNothingUsableRemains() {
        assertThatThrownBy(() -> Username.suggestionFrom("!!!"))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void suffixIsAppended() {
        assertThat(new Username("ada").withSuffix(2).value()).isEqualTo("ada2");
    }

    @Test
    void suffixTruncatesTheStemToStayWithinTheLimit() {
        Username atLimit = new Username("a".repeat(Username.MAX_LENGTH));

        Username suffixed = atLimit.withSuffix(12);

        assertThat(suffixed.value()).hasSize(Username.MAX_LENGTH).endsWith("12");
    }
}
