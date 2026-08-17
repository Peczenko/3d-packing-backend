package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.packing.event.PackingJobFinished;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PackingJobFinishedEmailRendererTest {

    private static final Instant      STARTED  = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant      FINISHED = Instant.parse("2026-08-01T10:01:23Z");
    private static final PackingJobId JOB_ID   = PackingJobId.generate();
    private static final ProjectId    PROJECT  = ProjectId.generate();
    private static final UserId       USER     = UserId.generate();

    private final PackingJobFinishedEmailRenderer renderer = new PackingJobFinishedEmailRenderer(new EmailTemplates());

    @Test
    void aSucceededJobCarriesTheResultMetadata() {
        EmailMessage message = renderer.render(succeeded(), requester(), "Chassis packing");

        assertThat(message.to()).containsExactly("pilot@example.com");
        assertThat(message.subject()).isEqualTo("Packing job succeeded - Chassis packing");
        assertThat(message.htmlBody()).contains("Packing job succeeded")
                                      .contains("Chassis packing")
                                      .contains(JOB_ID.toString())
                                      .contains("1 m 23 s")
                                      .contains("result.bin")
                                      .contains("2,048 bytes")
                                      .doesNotContain("Reason");
        assertThat(message.textBody()).contains("Result file: result.bin");
    }

    @Test
    void aFailedJobCarriesTheReasonAndNoResultRows() {
        EmailMessage message = renderer.render(failed(), requester(), "Chassis packing");

        assertThat(message.subject()).isEqualTo("Packing job failed - Chassis packing");
        assertThat(message.htmlBody()).contains("Packing job failed")
                                      .contains("engine exited 1")
                                      .doesNotContain("Result file");
    }

    @Test
    void aJobThatNeverStartedReportsNoRuntime() {
        PackingJobFinished neverStarted = new PackingJobFinished(JOB_ID,
                                                                 PROJECT,
                                                                 USER,
                                                                 PackingJobStatus.FAILED,
                                                                 "Dispatch expired before the job started",
                                                                 null,
                                                                 null,
                                                                 null,
                                                                 FINISHED);

        EmailMessage message = renderer.render(neverStarted, requester(), "Chassis packing");

        assertThat(message.htmlBody()).doesNotContain("Runtime");
    }

    @Test
    void aProjectNameFromTheUserIsEscapedRatherThanMailedAsMarkup() {
        EmailMessage message = renderer.render(succeeded(), requester(), "<script>alert(1)</script>");

        assertThat(message.htmlBody()).doesNotContain("<script>")
                                      .contains("&lt;script&gt;");
    }

    @Test
    void wrapsTheBodyInTheSharedLayoutAndStripsTheAuthoringComments() {
        EmailMessage message = renderer.render(succeeded(), requester(), "Chassis packing");

        assertThat(message.htmlBody()).contains("<style>")
                                      .contains("class=\"card\"")
                                      .contains("because you started this packing job")
                                      .doesNotContain("th:text", "th:each", "th:replace", "<!--");
    }

    private static User requester() {
        return User.register(new FirebaseUid("firebase-uid"),
                             new Email("pilot@example.com"),
                             new Username("pilot"),
                             "Pilot",
                             STARTED);
    }

    private static PackingJobFinished succeeded() {
        return new PackingJobFinished(JOB_ID,
                                      PROJECT,
                                      USER,
                                      PackingJobStatus.SUCCEEDED,
                                      null,
                                      "result.bin",
                                      2048L,
                                      STARTED,
                                      FINISHED);
    }

    private static PackingJobFinished failed() {
        return new PackingJobFinished(JOB_ID,
                                      PROJECT,
                                      USER,
                                      PackingJobStatus.FAILED,
                                      "engine exited 1",
                                      null,
                                      null,
                                      STARTED,
                                      FINISHED);
    }
}
