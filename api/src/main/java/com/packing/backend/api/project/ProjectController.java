package com.packing.backend.api.project;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.api.shared.security.CurrentUser;
import com.packing.backend.core.project.ProjectApplicationService;
import com.packing.backend.core.project.ProjectApplicationService.ChangeAccessCommand;
import com.packing.backend.core.project.ProjectApplicationService.CreateProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.GrantAccessCommand;
import com.packing.backend.core.project.ProjectApplicationService.ListProjectMembersQuery;
import com.packing.backend.core.project.ProjectApplicationService.ListProjectsCommand;
import com.packing.backend.core.project.ProjectApplicationService.ProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.ProjectQuery;
import com.packing.backend.core.project.ProjectApplicationService.RenameProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.RevokeAccessCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Projects and their members")
public class ProjectController {

    private final ProjectApplicationService projects;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createProject", summary = "Create a project")
    @ApiResponse(responseCode = "201", description = "Project created")
    public ProjectResponse create(@CurrentUser AuthenticatedUser caller,
                                  @Valid @RequestBody CreateProjectRequest request) {
        return ProjectResponse.from(projects.createProject(
                                                           new CreateProjectCommand(caller.firebaseUid(), request.name())));
    }

    @GetMapping
    @Operation(operationId = "listProjects", summary = "List the projects the caller can access")
    @ApiResponse(responseCode = "200", description = "Page of projects")
    public ProjectPageResponse list(
                                    @CurrentUser AuthenticatedUser caller,
                                    @Valid @ModelAttribute ProjectListRequest request) {
        return ProjectPageResponse.from(projects.listProjects(
                                                              new ListProjectsCommand(caller.firebaseUid(), request.toCriteria())));
    }

    @GetMapping("/{projectId}")
    @Operation(operationId = "getProject", summary = "Get a project")
    @ApiResponse(responseCode = "200", description = "The project")
    public ProjectResponse get(@CurrentUser AuthenticatedUser caller,
                               @PathVariable UUID projectId) {
        return ProjectResponse.from(projects.getProject(
                                                        new ProjectQuery(caller.firebaseUid(), projectId)));
    }

    @PatchMapping("/{projectId}")
    @Operation(operationId = "renameProject", summary = "Rename a project")
    @ApiResponse(responseCode = "200", description = "The renamed project")
    public ProjectResponse rename(@CurrentUser AuthenticatedUser caller,
                                  @PathVariable UUID projectId,
                                  @Valid @RequestBody RenameProjectRequest request) {
        return ProjectResponse.from(projects.renameProject(
                                                           new RenameProjectCommand(caller.firebaseUid(), projectId, request.name())));
    }

    @PostMapping("/{projectId}/disable")
    @Operation(operationId = "disableProject", summary = "Disable a project")
    @ApiResponse(responseCode = "204", description = "Project disabled")
    public ResponseEntity<Void> disable(@CurrentUser AuthenticatedUser caller,
                                        @PathVariable UUID projectId) {
        projects.disableProject(new ProjectCommand(caller.firebaseUid(), projectId));
        return ResponseEntity.noContent()
                             .build();
    }

    @PostMapping("/{projectId}/activate")
    @Operation(operationId = "activateProject", summary = "Re-activate a disabled project")
    @ApiResponse(responseCode = "204", description = "Project activated")
    public ResponseEntity<Void> activate(@CurrentUser AuthenticatedUser caller,
                                         @PathVariable UUID projectId) {
        projects.activateProject(new ProjectCommand(caller.firebaseUid(), projectId));
        return ResponseEntity.noContent()
                             .build();
    }

    @DeleteMapping("/{projectId}")
    @Operation(operationId = "deleteProject", summary = "Delete a project")
    @ApiResponse(responseCode = "204", description = "Project deleted")
    public ResponseEntity<Void> delete(@CurrentUser AuthenticatedUser caller,
                                       @PathVariable UUID projectId) {
        projects.deleteProject(new ProjectCommand(caller.firebaseUid(), projectId));
        return ResponseEntity.noContent()
                             .build();
    }

    @GetMapping("/{projectId}/members")
    @Operation(operationId = "listProjectMembers", summary = "List the members of a project")
    @ApiResponse(responseCode = "200", description = "Members of the project")
    public ProjectMemberPageResponse members(@CurrentUser AuthenticatedUser caller,
                                             @PathVariable UUID projectId,
                                             @Valid @ModelAttribute ProjectMemberListRequest request) {
        return ProjectMemberPageResponse.from(projects.listProjectMembers(
                                                                          new ListProjectMembersQuery(caller.firebaseUid(),
                                                                                                      projectId,
                                                                                                      request.toCriteria())));
    }

    @PostMapping("/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "addProjectMember", summary = "Grant a user access to a project")
    @ApiResponse(responseCode = "201", description = "The project including the new member")
    public ProjectResponse addMember(@CurrentUser AuthenticatedUser caller,
                                     @PathVariable UUID projectId,
                                     @Valid @RequestBody AddProjectMemberRequest request) {
        return ProjectResponse.from(projects.grantAccess(new GrantAccessCommand(
                                                                                caller.firebaseUid(),
                                                                                projectId,
                                                                                request.userId(),
                                                                                request.permission())));
    }

    @PatchMapping("/{projectId}/members/{userId}")
    @Operation(operationId = "changeProjectMemberPermission",
               summary = "Change a member's permission on a project")
    @ApiResponse(responseCode = "200", description = "The updated project")
    public ProjectResponse changeMemberPermission(
                                                  @CurrentUser AuthenticatedUser caller,
                                                  @PathVariable UUID projectId,
                                                  @PathVariable UUID userId,
                                                  @Valid @RequestBody ChangeMemberPermissionRequest request) {
        return ProjectResponse.from(projects.changeAccess(new ChangeAccessCommand(
                                                                                  caller.firebaseUid(),
                                                                                  projectId,
                                                                                  userId,
                                                                                  request.permission())));
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    @Operation(operationId = "removeProjectMember", summary = "Revoke a user's access to a project")
    @ApiResponse(responseCode = "204", description = "Member removed")
    public ResponseEntity<Void> removeMember(@CurrentUser AuthenticatedUser caller,
                                             @PathVariable UUID projectId,
                                             @PathVariable UUID userId) {
        projects.revokeAccess(new RevokeAccessCommand(caller.firebaseUid(), projectId, userId));
        return ResponseEntity.noContent()
                             .build();
    }
}
