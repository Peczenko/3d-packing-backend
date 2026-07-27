package com.packing.backend.api.project;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.api.shared.security.CurrentUser;
import com.packing.backend.core.project.ProjectApplicationService;
import com.packing.backend.core.project.ProjectApplicationService.ChangeAccessCommand;
import com.packing.backend.core.project.ProjectApplicationService.CreateProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.GrantAccessCommand;
import com.packing.backend.core.project.ProjectApplicationService.ListProjectsCommand;
import com.packing.backend.core.project.ProjectApplicationService.ProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.ProjectQuery;
import com.packing.backend.core.project.ProjectApplicationService.RenameProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.RevokeAccessCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectApplicationService projects;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@CurrentUser AuthenticatedUser caller,
                                  @Valid @RequestBody CreateProjectRequest request) {
        return ProjectResponse.from(projects.createProject(
                new CreateProjectCommand(caller.firebaseUid(), request.name())));
    }

    @GetMapping
    public ProjectPageResponse list(
            @CurrentUser AuthenticatedUser caller,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ListProjectsCommand.MAX_SIZE) int size) {
        return ProjectPageResponse.from(projects.listProjects(
                new ListProjectsCommand(caller.firebaseUid(), page, size)));
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@CurrentUser AuthenticatedUser caller,
                               @PathVariable UUID projectId) {
        return ProjectResponse.from(projects.getProject(
                new ProjectQuery(caller.firebaseUid(), projectId)));
    }

    @PatchMapping("/{projectId}")
    public ProjectResponse rename(@CurrentUser AuthenticatedUser caller,
                                  @PathVariable UUID projectId,
                                  @Valid @RequestBody RenameProjectRequest request) {
        return ProjectResponse.from(projects.renameProject(
                new RenameProjectCommand(caller.firebaseUid(), projectId, request.name())));
    }

    /** Makes the project a read-only archive: reads keep working, every write is refused. */
    @PostMapping("/{projectId}/disable")
    public ResponseEntity<Void> disable(@CurrentUser AuthenticatedUser caller,
                                        @PathVariable UUID projectId) {
        projects.disableProject(new ProjectCommand(caller.firebaseUid(), projectId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{projectId}/activate")
    public ResponseEntity<Void> activate(@CurrentUser AuthenticatedUser caller,
                                         @PathVariable UUID projectId) {
        projects.activateProject(new ProjectCommand(caller.firebaseUid(), projectId));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(@CurrentUser AuthenticatedUser caller,
                                       @PathVariable UUID projectId) {
        projects.deleteProject(new ProjectCommand(caller.firebaseUid(), projectId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{projectId}/members")
    public List<ProjectMemberResponse> members(@CurrentUser AuthenticatedUser caller,
                                               @PathVariable UUID projectId) {
        return projects.getProject(new ProjectQuery(caller.firebaseUid(), projectId))
                .members()
                .stream()
                .map(ProjectMemberResponse::from)
                .toList();
    }

    @PostMapping("/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse addMember(@CurrentUser AuthenticatedUser caller,
                                     @PathVariable UUID projectId,
                                     @Valid @RequestBody AddProjectMemberRequest request) {
        return ProjectResponse.from(projects.grantAccess(new GrantAccessCommand(
                caller.firebaseUid(), projectId, request.identifier(), request.permission())));
    }

    /** Re-levels an existing member only; adding someone goes through the POST above. */
    @PatchMapping("/{projectId}/members/{userId}")
    public ProjectResponse changeMemberPermission(
            @CurrentUser AuthenticatedUser caller,
            @PathVariable UUID projectId,
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeMemberPermissionRequest request) {
        return ProjectResponse.from(projects.changeAccess(new ChangeAccessCommand(
                caller.firebaseUid(), projectId, userId, request.permission())));
    }

    /** Also the "leave project" route: a member may always remove themselves. */
    @DeleteMapping("/{projectId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@CurrentUser AuthenticatedUser caller,
                                             @PathVariable UUID projectId,
                                             @PathVariable UUID userId) {
        projects.revokeAccess(new RevokeAccessCommand(caller.firebaseUid(), projectId, userId));
        return ResponseEntity.noContent().build();
    }
}
