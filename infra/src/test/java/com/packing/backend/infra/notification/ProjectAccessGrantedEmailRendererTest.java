package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.event.ProjectAccessGranted;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectAccessGrantedEmailRendererTest {

    private static final Instant   WHEN    = Instant.parse("2026-08-01T10:00:00Z");
    private static final ProjectId PROJECT = ProjectId.generate();
    private static final UserId    MEMBER  = UserId.generate();
    private static final UserId    GRANTER = UserId.generate();

    private final ProjectAccessGrantedEmailRenderer renderer = new ProjectAccessGrantedEmailRenderer(new EmailTemplates());

    @Test
    void namesTheProjectInTheSubjectAndMailsTheNewMember() {
        EmailMessage message = renderer.render(granted(ProjectPermission.WRITE, "Chassis packing"),
                                               member(),
                                               "Alice");

        assertThat(message.to()).containsExactly("pilot@example.com");
        assertThat(message.subject()).isEqualTo("You have been added to Chassis packing");
        assertThat(message.htmlBody()).contains("Chassis packing")
                                      .contains("Alice");
        assertThat(message.textBody()).contains("Alice added you to the project \"Chassis packing\"");
    }

    @Test
    void spellsOutWhatEachPermissionLets() {
        assertThat(render(ProjectPermission.READ).htmlBody())
                                                             .contains("Can view")
                                                             .contains("view this project and download its files");
        assertThat(render(ProjectPermission.WRITE).htmlBody())
                                                              .contains("Can edit")
                                                              .contains("add, rename and delete its files");
        assertThat(render(ProjectPermission.OWNER).htmlBody())
                                                              .contains("Owner")
                                                              .contains("manage this project, including its members");
    }

    @Test
    void aProjectNameFromTheUserIsEscapedRatherThanMailedAsMarkup() {
        EmailMessage message = renderer.render(granted(ProjectPermission.READ, "<script>alert(1)</script>"),
                                               member(),
                                               "Alice");

        assertThat(message.htmlBody()).doesNotContain("<script>")
                                      .contains("&lt;script&gt;");
    }

    @Test
    void wrapsTheBodyInTheSharedLayoutAndStripsTheAuthoringComments() {
        EmailMessage message = render(ProjectPermission.READ);

        assertThat(message.htmlBody()).contains("<style>")
                                      .contains("class=\"card\"")
                                      .contains("Alice added you to a project")
                                      .doesNotContain("th:text", "th:each", "th:replace", "<!--");
    }

    private EmailMessage render(ProjectPermission permission) {
        return renderer.render(granted(permission, "Chassis packing"), member(), "Alice");
    }

    private static ProjectAccessGranted granted(ProjectPermission permission, String projectName) {
        return new ProjectAccessGranted(PROJECT,
                                        new ProjectName(projectName),
                                        MEMBER,
                                        permission,
                                        GRANTER,
                                        WHEN);
    }

    private static User member() {
        return User.register(new FirebaseUid("firebase-uid"),
                             new Email("pilot@example.com"),
                             new Username("pilot"),
                             "Pilot",
                             WHEN);
    }
}
