package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.core.notification.port.out.EmailSender;
import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.user.Email;
import com.packing.backend.domain.user.FirebaseUid;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.Username;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationMailerTest {

    private static final Instant WHEN      = Instant.parse("2026-08-01T10:00:00Z");
    private static final UserId  RECIPIENT = UserId.generate();

    private final UserRepository users       = mock(UserRepository.class);
    private final EmailSender    emailSender = mock(EmailSender.class);

    private final NotificationMailer mailer = new NotificationMailer(users, emailSender);

    @Test
    void sendsWhatTheCallerComposedForTheResolvedRecipient() {
        when(users.findById(RECIPIENT)).thenReturn(Optional.of(registeredUser("Pilot")));
        EmailMessage composed = anyMessage();

        mailer.mailTo(RECIPIENT, "Something happened", recipient -> {
            assertThat(recipient.email()
                                .value()).isEqualTo("pilot@example.com");
            return composed;
        });

        verify(emailSender).send(composed);
    }

    @Test
    void anUnknownRecipientIsNotMailed() {
        when(users.findById(RECIPIENT)).thenReturn(Optional.empty());

        mailer.mailTo(RECIPIENT, "Something happened", recipient -> anyMessage());

        verify(emailSender, never()).send(any());
    }

    @Test
    void aDeletedRecipientIsNotMailed() {
        User deleted = registeredUser("Pilot");
        deleted.delete(WHEN);
        when(users.findById(RECIPIENT)).thenReturn(Optional.of(deleted));

        mailer.mailTo(RECIPIENT, "Something happened", recipient -> anyMessage());

        verify(emailSender, never()).send(any());
    }

    @Test
    void aSendFailureIsSwallowedBecauseTheCommitCannotBeUndone() {
        when(users.findById(RECIPIENT)).thenReturn(Optional.of(registeredUser("Pilot")));
        doThrow(new IllegalStateException("brevo unavailable")).when(emailSender)
                                                               .send(any());

        mailer.mailTo(RECIPIENT, "Something happened", recipient -> anyMessage());

        verify(emailSender).send(any());
    }

    @Test
    void aRenderFailureIsSwallowedForTheSameReason() {
        when(users.findById(RECIPIENT)).thenReturn(Optional.of(registeredUser("Pilot")));

        mailer.mailTo(RECIPIENT, "Something happened", recipient -> {
            throw new IllegalStateException("template blew up");
        });

        verify(emailSender, never()).send(any());
    }

    @Test
    void aLookupFailureIsSwallowedForTheSameReason() {
        when(users.findById(RECIPIENT)).thenThrow(new IllegalStateException("database unreachable"));

        mailer.mailTo(RECIPIENT, "Something happened", recipient -> anyMessage());

        verify(emailSender, never()).send(any());
    }

    @Test
    void namesAUserByDisplayNameAndFallsBackToUsernameThenId() {
        when(users.findById(RECIPIENT)).thenReturn(Optional.of(registeredUser("Pilot")));
        assertThat(mailer.displayNameOf(RECIPIENT)).isEqualTo("Pilot");

        when(users.findById(RECIPIENT)).thenReturn(Optional.of(registeredUser(null)));
        assertThat(mailer.displayNameOf(RECIPIENT)).isEqualTo("pilot");

        when(users.findById(RECIPIENT)).thenReturn(Optional.empty());
        assertThat(mailer.displayNameOf(RECIPIENT)).isEqualTo(RECIPIENT.toString());
    }

    private static EmailMessage anyMessage() {
        return EmailMessage.to("pilot@example.com")
                           .subject("Something happened")
                           .text("body")
                           .build();
    }

    private static User registeredUser(String displayName) {
        return User.register(new FirebaseUid("firebase-uid"),
                             new Email("pilot@example.com"),
                             new Username("pilot"),
                             displayName,
                             WHEN);
    }
}
