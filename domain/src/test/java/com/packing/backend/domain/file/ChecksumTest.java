package com.packing.backend.domain.file;

import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChecksumTest {

    /** SHA-256 of the empty input. */
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void acceptsALowerCaseHexDigest() {
        assertThat(Checksum.ofHex(EMPTY_SHA256).value()).isEqualTo(EMPTY_SHA256);
    }

    @Test
    void normalisesUpperCaseToLowerSoStoredDigestsCompareEqual() {
        assertThat(Checksum.ofHex(EMPTY_SHA256.toUpperCase()))
                .isEqualTo(Checksum.ofHex(EMPTY_SHA256));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "zz", " "})
    void rejectsAnythingThatIsNotASha256Digest(String value) {
        assertThatThrownBy(() -> Checksum.ofHex(value))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void rejectsNonHexCharactersOfTheRightLength() {
        assertThatThrownBy(() -> Checksum.ofHex("g".repeat(Checksum.HEX_LENGTH)))
                .isInstanceOf(DomainRuleViolationException.class);
    }
}
