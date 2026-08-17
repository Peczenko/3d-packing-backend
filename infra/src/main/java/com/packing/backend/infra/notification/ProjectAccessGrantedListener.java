package com.packing.backend.infra.notification;

import com.packing.backend.domain.project.event.ProjectAccessGranted;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ProjectAccessGrantedListener {

    private final NotificationMailer                mailer;
    private final ProjectAccessGrantedEmailRenderer renderer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAccessGranted(ProjectAccessGranted event) {
        mailer.mailTo(event.userId(),
                      "Access to project " + event.projectId() + " was granted to user " + event.userId(),
                      member -> renderer.render(event, member, mailer.displayNameOf(event.grantedBy())));
    }
}
