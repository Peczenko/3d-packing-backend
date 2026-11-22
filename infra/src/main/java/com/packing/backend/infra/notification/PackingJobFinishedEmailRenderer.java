package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.domain.packing.PackingJobStatus;
import com.packing.backend.domain.packing.event.PackingJobFinished;
import com.packing.backend.domain.user.User;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class PackingJobFinishedEmailRenderer {

    private static final String FOOTER_NOTE = "You are receiving this because you started this packing job.";

    private final EmailTemplates templates;

    EmailMessage render(PackingJobFinished event, User requester, String projectName) {
        boolean succeeded = event.status() == PackingJobStatus.SUCCEEDED;
        String headline = succeeded ? "Packing job succeeded" : "Packing job failed";
        String summary = succeeded
                                   ? "Open the project to see the preview and download the result."
                                   : "No result was produced. You can submit the job again once the cause is resolved.";
        MailFields fields = fieldsOf(event, projectName);

        return EmailMessage.to(requester.email()
                                        .value())
                           .subject(headline + " - " + projectName)
                           .html(templates.render("packing-job-finished",
                                                  Map.of(
                                                         "headline",
                                                         headline,
                                                         "projectName",
                                                         projectName,
                                                         "fields",
                                                         fields.asMap(),
                                                         "summary",
                                                         summary,
                                                         "footerNote",
                                                         FOOTER_NOTE)))
                           .text(headline + "\n\n" + fields.asText() + "\n\n" + summary)
                           .build();
    }

    private MailFields fieldsOf(PackingJobFinished event, String projectName) {
        MailFields fields = new MailFields();
        fields.put("Project", projectName);
        fields.put("Job", event.jobId());
        fields.put("Status", event.status());
        fields.putIfPresent("Runtime", runtimeOf(event), PackingJobFinishedEmailRenderer::describe);
        fields.putIfPresent("Reason", event.failureReason());
        return fields;
    }

    private static Duration runtimeOf(PackingJobFinished event) {
        return event.startedAt() == null ? null : Duration.between(event.startedAt(), event.occurredAt());
    }

    private static String describe(Duration runtime) {
        long seconds = Math.max(runtime.toSeconds(), 0);
        long minutes = seconds / 60;
        return minutes == 0 ? seconds + " s" : minutes + " m " + seconds % 60 + " s";
    }
}
