package com.packing.backend.domain.file;

import com.packing.backend.domain.shared.DomainRuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileNameTest {

    private static final char CARRIAGE_RETURN = 0x0D;
    private static final char LINE_FEED = 0x0A;
    private static final char TAB = 0x09;
    private static final char DELETE = 0x7F;
    private static final char COMBINING_ACUTE = 0x0301;

    @Test
    void keepsAPlainNameAsGiven() {
        assertThat(new FileName("Bracket_v2.STL").value()).isEqualTo("Bracket_v2.STL");
    }

    @Test
    void reducesATraversalAttemptToTheBareName() {
        assertThat(new FileName("../../etc/passwd.stl").value()).isEqualTo("passwd.stl");
        assertThat(new FileName("/var/lib/model.stl").value()).isEqualTo("model.stl");
        assertThat(new FileName("C:" + windows("windows") + windows("model.stl")).value())
                .isEqualTo("model.stl");
    }

    private static String windows(String segment) {
        return (char) 0x5C + segment;
    }

    @Test
    void rejectsControlCharactersThatCouldInjectAHeader() {
        assertThatThrownBy(() -> new FileName("model" + CARRIAGE_RETURN + LINE_FEED + "X: y.stl"))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("control characters");
        assertThatThrownBy(() -> new FileName("model" + TAB + "part.stl"))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("control characters");
        assertThatThrownBy(() -> new FileName("model" + DELETE + "part.stl"))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("control characters");
    }

    @Test
    void normalisesToNfcSoTheStoredNameMatchesWhatTheUserSees() {
        String decomposed = "mode" + COMBINING_ACUTE + "l.stl";

        FileName name = new FileName(decomposed);

        assertThat(decomposed).isNotEqualTo("model.stl");
        assertThat(name.value()).isEqualTo(Normalizer.normalize(decomposed, Normalizer.Form.NFC));
        assertThat(Normalizer.isNormalized(name.value(), Normalizer.Form.NFC)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "model.", ".stl", "."})
    void rejectsANameWithoutAUsableExtension(String name) {
        assertThatThrownBy(() -> new FileName(name))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "/", "..", "./"})
    void rejectsBlankAndPathOnlyNames(String name) {
        assertThatThrownBy(() -> new FileName(name))
                .isInstanceOf(DomainRuleViolationException.class);
    }

    @Test
    void rejectsANameLongerThanTheColumn() {
        String tooLong = "a".repeat(FileName.MAX_LENGTH) + ".stl";

        assertThatThrownBy(() -> new FileName(tooLong))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void acceptsANameExactlyAtTheLimit() {
        String exactly = "a".repeat(FileName.MAX_LENGTH - ".stl".length()) + ".stl";

        assertThat(new FileName(exactly).value()).hasSize(FileName.MAX_LENGTH);
    }

    @Test
    void exposesTheExtensionLowerCasedAndWithoutTheDot() {
        assertThat(new FileName("Bracket.STL").extension()).isEqualTo("stl");
        assertThat(new FileName("part.v2.stp").extension()).isEqualTo("stp");
    }

    @Test
    void resolvesTheFormatFromTheExtension() {
        assertThat(new FileName("part.stp").format()).isEqualTo(ModelFormat.STEP);
    }

    @Test
    void rejectsAnUnsupportedFormatOnlyWhenTheFormatIsAsked() {
        FileName name = new FileName("notes.txt");

        assertThatThrownBy(name::format)
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("Unsupported 3D model format");
    }
}
