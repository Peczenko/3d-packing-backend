package com.packing.backend.infra.notification;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class MailFieldsTest {

    @Test
    void keepsTheInsertionOrderTheTemplateRenders() {
        MailFields fields = new MailFields();
        fields.put("Project", "Chassis packing");
        fields.put("Job", 42);
        fields.put("Status", "SUCCEEDED");

        assertThat(fields.asMap()).containsExactly(entry("Project", "Chassis packing"),
                                                   entry("Job", "42"),
                                                   entry("Status", "SUCCEEDED"));
        assertThat(fields.asText()).isEqualTo("""
            Project: Chassis packing
            Job: 42
            Status: SUCCEEDED""");
    }

    @Test
    void putKeepsTheRowAndMarksTheValueAbsent() {
        MailFields fields = new MailFields();
        fields.put("Message", null);
        fields.put("Handler", "  ");

        assertThat(fields.asMap()).containsExactly(entry("Message", MailFields.ABSENT),
                                                   entry("Handler", MailFields.ABSENT));
    }

    @Test
    void putIfPresentDropsTheRowEntirely() {
        MailFields fields = new MailFields();
        fields.put("Status", "FAILED");
        fields.putIfPresent("Result file", null);
        fields.putIfPresent("Reason", "engine exited 1");

        assertThat(fields.asMap()).containsExactly(entry("Status", "FAILED"),
                                                   entry("Reason", "engine exited 1"));
    }

    @Test
    void putIfPresentFormatsOnlyWhatIsThere() {
        MailFields fields = new MailFields();
        fields.putIfPresent("Result size", 2048L, size -> String.format(Locale.ROOT, "%,d bytes", size));
        fields.putIfPresent("Runtime", (Long) null, size -> String.format(Locale.ROOT, "%,d bytes", size));

        assertThat(fields.asMap()).containsExactly(entry("Result size", "2,048 bytes"));
    }

    @Test
    void isNotWritableThroughTheRenderedMap() {
        MailFields fields = new MailFields();
        fields.put("Status", "FAILED");

        assertThatThrownBy(() -> fields.asMap()
                                       .put("Status", "SUCCEEDED"))
                                                                   .isInstanceOf(UnsupportedOperationException.class);
    }
}
