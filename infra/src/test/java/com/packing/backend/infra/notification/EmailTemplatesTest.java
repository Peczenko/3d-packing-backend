package com.packing.backend.infra.notification;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTemplatesTest {

    private final EmailTemplates templates = new EmailTemplates();

    @Test
    void wrapsATemplateInTheSharedLayoutAndItsFooterNote() {
        String html = templates.render("project-access-granted", model("Because Alice added you."));

        assertThat(html).contains("class=\"card\"")
                        .contains("Because Alice added you.");
    }

    @Test
    void rejectsATemplateThatForgotWhyTheRecipientIsGettingTheMail() {
        Map<String, Object> model = model("Because Alice added you.");
        model.remove("footerNote");

        assertThatThrownBy(() -> templates.render("project-access-granted", model))
                                                                                   .isInstanceOf(IllegalStateException.class)
                                                                                   .hasMessageContaining("footerNote");
    }

    @Test
    void rejectsABlankFooterNoteForTheSameReason() {
        assertThatThrownBy(() -> templates.render("project-access-granted", model("  ")))
                                                                                         .isInstanceOf(IllegalStateException.class);
    }

    private static Map<String, Object> model(String footerNote) {
        Map<String, Object> model = new HashMap<>();
        model.put("projectName", "Chassis packing");
        model.put("permission", "Can edit");
        model.put("grantedBy", "Alice");
        model.put("summary", "You can view this project.");
        model.put("footerNote", footerNote);
        return model;
    }
}
