package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.core.notification.port.out.EmailSender;
import com.packing.backend.core.user.port.out.UserRepository;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.event.ProjectAccessGranted;
import com.packing.backend.domain.user.User;
import com.packing.backend.domain.user.UserId;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
class ProjectAccessGrantedListener {

    private final UserRepository users;
    private final EmailSender    emailSender;
    private final EmailTemplates templates;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAccessGranted(ProjectAccessGranted event) {
        try {
            Optional<User> member = users.findById(event.userId());
            if (member.isEmpty() || member.get()
                                          .isDeleted()) {
                log.warn("Access to project {} was granted to user {}, who has no active profile to notify.",
                         event.projectId(),
                         event.userId());
                return;
            }
            emailSender.send(compose(event, member.get()));
        } catch (RuntimeException e) {
            log.error("Access to project {} was granted to user {} but the notification email could not be sent. The membership is unaffected.",
                      event.projectId(),
                      event.userId(),
                      e);
        }
    }

    private EmailMessage compose(ProjectAccessGranted event, User member) {
        String projectName = event.projectName()
                                  .value();
        String grantedBy = displayNameOf(event.grantedBy());
        String permission = describe(event.permission());

        return EmailMessage.to(member.email()
                                     .value())
                           .subject("You have been added to " + projectName)
                           .html(templates.render("project-access-granted",
                                                  Map.of(
                                                         "projectName",
                                                         projectName,
                                                         "permission",
                                                         permission,
                                                         "grantedBy",
                                                         grantedBy,
                                                         "summary",
                                                         summary(event.permission()),
                                                         "footerNote",
                                                         "You are receiving this because " + grantedBy
                                                                 + " added you to a project.")))
                           .text(grantedBy + " added you to the project \"" + projectName + "\".\n"
                                   + "Your access: " + permission + "\n"
                                   + summary(event.permission()))
                           .build();
    }

    private String displayNameOf(UserId userId) {
        return users.findById(userId)
                    .map(user -> user.displayName() == null
                                                            ? user.username()
                                                                  .value()
                                                            : user.displayName())
                    .orElseGet(userId::toString);
    }

    private String describe(ProjectPermission permission) {
        return switch (permission) {
            case READ -> "Can view";
            case WRITE -> "Can edit";
            case OWNER -> "Owner";
        };
    }

    private String summary(ProjectPermission permission) {
        return switch (permission) {
            case READ -> "You can view this project and download its files.";
            case WRITE -> "You can view this project and add, rename and delete its files.";
            case OWNER -> "You can manage this project, including its members.";
        };
    }
}
