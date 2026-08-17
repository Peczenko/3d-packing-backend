package com.packing.backend.infra.notification;

import com.packing.backend.core.notification.EmailMessage;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.event.ProjectAccessGranted;
import com.packing.backend.domain.user.User;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ProjectAccessGrantedEmailRenderer {

    private final EmailTemplates templates;

    EmailMessage render(ProjectAccessGranted event, User member, String grantedBy) {
        String projectName = event.projectName()
                                  .value();
        String permission = describe(event.permission());
        String summary = summary(event.permission());

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
                                                         summary,
                                                         "footerNote",
                                                         "You are receiving this because " + grantedBy
                                                                 + " added you to a project.")))
                           .text(grantedBy + " added you to the project \"" + projectName + "\".\n"
                                   + "Your access: " + permission + "\n"
                                   + summary)
                           .build();
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
