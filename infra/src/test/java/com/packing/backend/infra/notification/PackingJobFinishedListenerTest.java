package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.core.notification.port.out.EmailSender;
import com.packing.backend.core.project.port.out.ProjectRepository;
import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.packing.event.PackingJobFinished;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectName;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackingJobFinishedListenerTest {

    private static final Instant      STARTED  = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant      FINISHED = Instant.parse("2026-08-01T10:01:23Z");
    private static final PackingJobId JOB_ID   = PackingJobId.generate();
    private static final ProjectId    PROJECT  = ProjectId.generate();
    private static final UserId       USER     = UserId.generate();

    private final UserRepository                  users       = mock(UserRepository.class);
    private final ProjectRepository               projects    = mock(ProjectRepository.class);
    private final EmailSender                     emailSender = mock(EmailSender.class);
    private final PackingJobFinishedEmailRenderer renderer    = mock(PackingJobFinishedEmailRenderer.class);

    private final PackingJobFinishedListener listener = new PackingJobFinishedListener(new NotificationMailer(users,
                                                                                                              emailSender),
                                                                                       projects,
                                                                                       renderer);

    @Test
    void runsAfterCommit() throws NoSuchMethodException {
        Method method = PackingJobFinishedListener.class.getDeclaredMethod("onJobFinished",
                                                                           PackingJobFinished.class);
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void mailsTheRequesterWhatTheRendererComposed() {
        activeRequester();
        namedProject("Chassis packing");
        EmailMessage composed = anyMessage();
        when(renderer.render(any(), any(), any())).thenReturn(composed);

        listener.onJobFinished(succeeded());

        verify(renderer).render(any(PackingJobFinished.class), any(User.class), eq("Chassis packing"));
        verify(emailSender).send(composed);
    }

    @Test
    void aMissingProjectFallsBackToItsIdRatherThanLosingTheMail() {
        activeRequester();
        when(projects.findById(PROJECT)).thenReturn(Optional.empty());
        when(renderer.render(any(), any(), any())).thenReturn(anyMessage());

        listener.onJobFinished(succeeded());

        ArgumentCaptor<String> projectName = ArgumentCaptor.forClass(String.class);
        verify(renderer).render(any(), any(), projectName.capture());
        assertThat(projectName.getValue()).isEqualTo(PROJECT.toString());
    }

    private void activeRequester() {
        when(users.findById(USER)).thenReturn(Optional.of(registeredUser()));
    }

    private void namedProject(String name) {
        when(projects.findById(PROJECT)).thenReturn(
                                                    Optional.of(Project.create(new ProjectName(name), USER, STARTED)));
    }

    private static EmailMessage anyMessage() {
        return EmailMessage.to("pilot@example.com")
                           .subject("Packing job succeeded")
                           .text("body")
                           .build();
    }

    private static User registeredUser() {
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
}
