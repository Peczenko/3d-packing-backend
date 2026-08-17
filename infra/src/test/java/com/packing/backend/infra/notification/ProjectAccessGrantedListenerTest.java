package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.core.notification.port.out.EmailSender;
import com.packing.backend.core.user.port.out.UserRepository;
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

class ProjectAccessGrantedListenerTest {

    private static final Instant   WHEN    = Instant.parse("2026-08-01T10:00:00Z");
    private static final ProjectId PROJECT = ProjectId.generate();
    private static final UserId    MEMBER  = UserId.generate();
    private static final UserId    GRANTER = UserId.generate();

    private final UserRepository                    users       = mock(UserRepository.class);
    private final EmailSender                       emailSender = mock(EmailSender.class);
    private final ProjectAccessGrantedEmailRenderer renderer    = mock(ProjectAccessGrantedEmailRenderer.class);

    private final ProjectAccessGrantedListener listener = new ProjectAccessGrantedListener(new NotificationMailer(users,
                                                                                                                  emailSender),
                                                                                           renderer);

    @Test
    void runsAfterCommit() throws NoSuchMethodException {
        Method method = ProjectAccessGrantedListener.class.getDeclaredMethod("onAccessGranted",
                                                                             ProjectAccessGranted.class);
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void mailsTheNewMemberWhatTheRendererComposed() {
        activeMember();
        granter("Alice");
        EmailMessage composed = anyMessage();
        when(renderer.render(any(), any(), any())).thenReturn(composed);

        listener.onAccessGranted(granted());

        verify(renderer).render(any(ProjectAccessGranted.class), any(User.class), eq("Alice"));
        verify(emailSender).send(composed);
    }

    @Test
    void anUnknownGranterFallsBackToItsIdRatherThanLosingTheMail() {
        activeMember();
        when(users.findById(GRANTER)).thenReturn(Optional.empty());
        when(renderer.render(any(), any(), any())).thenReturn(anyMessage());

        listener.onAccessGranted(granted());

        ArgumentCaptor<String> grantedBy = ArgumentCaptor.forClass(String.class);
        verify(renderer).render(any(), any(), grantedBy.capture());
        assertThat(grantedBy.getValue()).isEqualTo(GRANTER.toString());
    }

    private void activeMember() {
        when(users.findById(MEMBER)).thenReturn(Optional.of(registeredUser()));
    }

    private void granter(String displayName) {
        User granter = User.register(new FirebaseUid("firebase-uid-granter"),
                                     new Email("alice@example.com"),
                                     new Username("alice"),
                                     displayName,
                                     WHEN);
        when(users.findById(GRANTER)).thenReturn(Optional.of(granter));
    }

    private static ProjectAccessGranted granted() {
        return new ProjectAccessGranted(PROJECT,
                                        new ProjectName("Chassis packing"),
                                        MEMBER,
                                        ProjectPermission.WRITE,
                                        GRANTER,
                                        WHEN);
    }

    private static EmailMessage anyMessage() {
        return EmailMessage.to("pilot@example.com")
                           .subject("You have been added to Chassis packing")
                           .text("body")
                           .build();
    }

    private static User registeredUser() {
        return User.register(new FirebaseUid("firebase-uid-member"),
                             new Email("pilot@example.com"),
                             new Username("pilot"),
                             "Pilot",
                             WHEN);
    }
}
