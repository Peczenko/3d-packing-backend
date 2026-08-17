package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.core.notification.port.out.EmailSender;
import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Function;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
class NotificationMailer {

    private final UserRepository users;
    private final EmailSender    emailSender;

    // occurrence is the committed change being reported, phrased as a clause the log messages
    // below complete: "Packing job 7 finished as SUCCEEDED but ...".
    void mailTo(UserId recipient, String occurrence, Function<User, EmailMessage> compose) {
        try {
            Optional<User> profile = users.findById(recipient);
            if (profile.isEmpty() || profile.get()
                                            .isDeleted()) {
                log.warn("{} but recipient {} has no active profile to notify.", occurrence, recipient);
                return;
            }
            emailSender.send(compose.apply(profile.get()));
        } catch (RuntimeException e) {
            // Every caller runs AFTER_COMMIT: the change this reports is already durable, so a
            // failed lookup, render or send has nothing left to roll back and must not escape.
            log.error("{} but the notification email could not be sent. The change is committed and "
                    + "readable through the API; only the notification is lost.", occurrence, e);
        }
    }

    String displayNameOf(UserId userId) {
        return users.findById(userId)
                    .map(NotificationMailer::nameOf)
                    .orElseGet(userId::toString);
    }

    private static String nameOf(User user) {
        String displayName = user.displayName();
        return displayName == null || displayName.isBlank() ? user.username()
                                                                  .value()
                                                            : displayName;
    }
}
