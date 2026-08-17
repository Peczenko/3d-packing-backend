package com.packing.backend.infra.notification;

import com.packing.backend.core.project.port.out.ProjectRepository;
import com.packing.backend.domain.packing.event.PackingJobFinished;
import com.packing.backend.domain.project.Project;
import com.packing.backend.domain.project.ProjectName;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class PackingJobFinishedListener {

    private final NotificationMailer              mailer;
    private final ProjectRepository               projects;
    private final PackingJobFinishedEmailRenderer renderer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onJobFinished(PackingJobFinished event) {
        mailer.mailTo(event.requestedBy(),
                      "Packing job " + event.jobId() + " finished as " + event.status(),
                      requester -> renderer.render(event, requester, projectNameOf(event)));
    }

    private String projectNameOf(PackingJobFinished event) {
        return projects.findById(event.projectId())
                       .map(Project::name)
                       .map(ProjectName::value)
                       .orElseGet(() -> event.projectId()
                                             .toString());
    }
}
