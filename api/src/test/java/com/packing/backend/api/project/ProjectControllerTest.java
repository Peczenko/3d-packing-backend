package com.packing.backend.api.project;

import com.packing.backend.core.notification.port.out.ErrorAlerter;
import com.packing.backend.core.project.ProjectApplicationService;
import com.packing.backend.core.project.ProjectApplicationService.ChangeAccessCommand;
import com.packing.backend.core.project.ProjectApplicationService.CreateProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.GrantAccessCommand;
import com.packing.backend.core.project.ProjectApplicationService.ListProjectsCommand;
import com.packing.backend.core.project.ProjectApplicationService.ProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.RevokeAccessCommand;
import com.packing.backend.core.project.ProjectMemberView;
import com.packing.backend.core.project.ProjectSummaryView;
import com.packing.backend.core.project.ProjectView;
import com.packing.backend.core.shared.Page;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectNotFoundException;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.shared.PermissionDeniedException;
import com.packing.backend.domain.shared.ResourceConflictException;
import com.packing.backend.domain.user.UserNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Filters are disabled: the real filter chain lives in {@code :app}, so the
 * {@code SecurityContextHolder} is populated directly instead.
 */
@WebMvcTest(controllers = ProjectController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProjectControllerTest {

    private static final String UID = "firebase-uid-1";
    private static final Instant NOW = Instant.parse("2026-07-27T10:15:30Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectApplicationService projects;

    @MockitoBean
    private ErrorAlerter alerter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(UID)
                .claim("email", "ada@example.com")
                .claim("name", "Ada Lovelace")
                .claim("email_verified", true)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static ProjectView view(UUID id, ProjectPermission mine) {
        return new ProjectView(id, "Chassis packing", ProjectStatus.ACTIVE, UUID.randomUUID(),
                mine,
                List.of(new ProjectMemberView(UUID.randomUUID(), "ada", "Ada Lovelace",
                        ProjectPermission.OWNER, NOW)),
                NOW, NOW);
    }

    @Test
    void createReturns201WithTheProject() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        when(projects.createProject(any())).thenReturn(view(id, ProjectPermission.OWNER));

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Chassis packing\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Chassis packing"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.myPermission").value("OWNER"))
                .andExpect(jsonPath("$.members.length()").value(1));

        ArgumentCaptor<CreateProjectCommand> command =
                ArgumentCaptor.forClass(CreateProjectCommand.class);
        verify(projects).createProject(command.capture());
        assertThat(command.getValue().firebaseUid()).isEqualTo(UID);
        assertThat(command.getValue().name()).isEqualTo("Chassis packing");
    }

    @Test
    void createRejectsABlankName() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void listReturnsAPageOfSummaries() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        when(projects.listProjects(any())).thenReturn(new Page<>(
                List.of(new ProjectSummaryView(id, "Chassis packing", ProjectStatus.ACTIVE,
                        ProjectPermission.WRITE, 3, NOW, NOW)),
                0, 20, 1L));

        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].myPermission").value("WRITE"))
                .andExpect(jsonPath("$.content[0].memberCount").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listDefaultsToTheFirstPage() throws Exception {
        authenticate();
        when(projects.listProjects(any())).thenReturn(new Page<>(List.of(), 0, 20, 0L));
        ArgumentCaptor<ListProjectsCommand> command =
                ArgumentCaptor.forClass(ListProjectsCommand.class);

        mockMvc.perform(get("/api/v1/projects")).andExpect(status().isOk());

        verify(projects).listProjects(command.capture());
        assertThat(command.getValue().page().page()).isZero();
        assertThat(command.getValue().page().size()).isEqualTo(20);
    }

    @Test
    void listRejectsAPageSizeAboveTheLimit() throws Exception {
        authenticate();

        mockMvc.perform(get("/api/v1/projects").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aNonMemberGets404RatherThan403() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        when(projects.getProject(any())).thenThrow(ProjectNotFoundException.byId(new ProjectId(id)));

        mockMvc.perform(get("/api/v1/projects/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    @Test
    void anUnderprivilegedMemberGets403() throws Exception {
        authenticate();
        when(projects.renameProject(any()))
                .thenThrow(new PermissionDeniedException("This action requires OWNER permission"));

        mockMvc.perform(patch("/api/v1/projects/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New name\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value("This action requires OWNER permission"));
    }

    @Test
    void disableAndActivateReturn204() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/projects/{id}/disable", id))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/projects/{id}/activate", id))
                .andExpect(status().isNoContent());

        verify(projects).disableProject(new ProjectCommand(UID, id));
        verify(projects).activateProject(new ProjectCommand(UID, id));
    }

    @Test
    void deleteReturns204() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/projects/{id}", id))
                .andExpect(status().isNoContent());

        verify(projects).deleteProject(new ProjectCommand(UID, id));
    }

    @Test
    void addMemberReturns201AndPassesTheIdentifierThrough() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        when(projects.grantAccess(any())).thenReturn(view(id, ProjectPermission.OWNER));

        mockMvc.perform(post("/api/v1/projects/{id}/members", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"bob@example.com\",\"permission\":\"WRITE\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<GrantAccessCommand> command =
                ArgumentCaptor.forClass(GrantAccessCommand.class);
        verify(projects).grantAccess(command.capture());
        assertThat(command.getValue().identifier()).isEqualTo("bob@example.com");
        assertThat(command.getValue().permission()).isEqualTo(ProjectPermission.WRITE);
    }

    @Test
    void addMemberRejectsAnUnknownPermission() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/v1/projects/{id}/members", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"bob@example.com\",\"permission\":\"GOD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addMemberSurfacesAnUnknownIdentifierAs404() throws Exception {
        authenticate();
        when(projects.grantAccess(any()))
                .thenThrow(new UserNotFoundException("No user matches that identifier"));

        mockMvc.perform(post("/api/v1/projects/{id}/members", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"ghost@example.com\",\"permission\":\"READ\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No user matches that identifier"));
    }

    /** PATCH must route to changeAccess, which cannot add anyone who is not already a member. */
    @Test
    void changingAPermissionRoutesToTheMemberOnlyUseCase() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(projects.changeAccess(any())).thenReturn(view(id, ProjectPermission.OWNER));

        mockMvc.perform(patch("/api/v1/projects/{id}/members/{userId}", id, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permission\":\"READ\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ChangeAccessCommand> command =
                ArgumentCaptor.forClass(ChangeAccessCommand.class);
        verify(projects).changeAccess(command.capture());
        assertThat(command.getValue().projectId()).isEqualTo(id);
        assertThat(command.getValue().userId()).isEqualTo(userId);
        assertThat(command.getValue().permission()).isEqualTo(ProjectPermission.READ);
    }

    @Test
    void demotingTheLastOwnerSurfacesAs409() throws Exception {
        authenticate();
        when(projects.changeAccess(any())).thenThrow(
                new ResourceConflictException("Project must keep at least one owner"));

        mockMvc.perform(patch("/api/v1/projects/{id}/members/{userId}",
                        UUID.randomUUID(), UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permission\":\"READ\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    void removingAMemberReturns204() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/projects/{id}/members/{userId}", id, userId))
                .andExpect(status().isNoContent());

        verify(projects).revokeAccess(new RevokeAccessCommand(UID, id, userId));
    }

    @Test
    void theLastOwnerLeavingSurfacesAs409() throws Exception {
        authenticate();
        doThrow(new ResourceConflictException("Project must keep at least one owner"))
                .when(projects).revokeAccess(any());

        mockMvc.perform(delete("/api/v1/projects/{id}/members/{userId}",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isConflict());
    }

    @Test
    void membersListsTheRoster() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        when(projects.getProject(any())).thenReturn(view(id, ProjectPermission.READ));

        mockMvc.perform(get("/api/v1/projects/{id}/members", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("ada"))
                .andExpect(jsonPath("$[0].permission").value("OWNER"));
    }

    @Test
    void anUnparseableProjectIdIsAClientErrorNotAServerError() throws Exception {
        authenticate();

        mockMvc.perform(get("/api/v1/projects/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
