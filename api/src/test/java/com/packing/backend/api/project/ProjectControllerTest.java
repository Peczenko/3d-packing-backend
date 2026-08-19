package com.packing.backend.api.project;

import com.packing.backend.core.notification.port.out.ErrorAlerter;
import com.packing.backend.core.project.ProjectApplicationService;
import com.packing.backend.core.project.ProjectApplicationService.ChangeAccessCommand;
import com.packing.backend.core.project.ProjectApplicationService.CreateProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.GrantAccessCommand;
import com.packing.backend.core.project.ProjectApplicationService.ListProjectMembersQuery;
import com.packing.backend.core.project.ProjectApplicationService.ListProjectsCommand;
import com.packing.backend.core.project.ProjectMemberListCriteria;
import com.packing.backend.core.project.ProjectListCriteria;
import com.packing.backend.core.project.ProjectApplicationService.ProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.ProjectQuery;
import com.packing.backend.core.project.ProjectApplicationService.RenameProjectCommand;
import com.packing.backend.core.project.ProjectApplicationService.RevokeAccessCommand;
import com.packing.backend.core.project.ProjectMemberView;
import com.packing.backend.core.project.ProjectSummaryView;
import com.packing.backend.core.project.ProjectView;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectNotFoundException;
import com.packing.backend.domain.project.ProjectPermission;
import com.packing.backend.domain.project.ProjectStatus;
import com.packing.backend.domain.shared.DomainRuleViolationException;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ProjectController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProjectControllerTest {

    private static final String  UID = "firebase-uid-1";
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
        SecurityContextHolder.getContext()
                             .setAuthentication(
                                                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static ProjectView view(UUID id, ProjectPermission mine) {
        return new ProjectView(id,
                               "Chassis packing",
                               ProjectStatus.ACTIVE,
                               UUID.randomUUID(),
                               mine,
                               NOW,
                               NOW);
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
               .andExpect(jsonPath("$.members").doesNotExist());

        ArgumentCaptor<CreateProjectCommand> command = ArgumentCaptor.forClass(CreateProjectCommand.class);
        verify(projects).createProject(command.capture());
        assertThat(command.getValue()
                          .firebaseUid()).isEqualTo(UID);
        assertThat(command.getValue()
                          .name()).isEqualTo("Chassis packing");
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
                                                                 List.of(new ProjectSummaryView(id,
                                                                                                "Chassis packing",
                                                                                                ProjectStatus.ACTIVE,
                                                                                                ProjectPermission.WRITE,
                                                                                                3,
                                                                                                NOW,
                                                                                                NOW)),
                                                                 0,
                                                                 20,
                                                                 1L));

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
        ArgumentCaptor<ListProjectsCommand> command = ArgumentCaptor.forClass(ListProjectsCommand.class);

        mockMvc.perform(get("/api/v1/projects"))
               .andExpect(status().isOk());

        verify(projects).listProjects(command.capture());
        assertThat(command.getValue()
                          .criteria()
                          .page()
                          .page()).isZero();
        assertThat(command.getValue()
                          .criteria()
                          .page()
                          .size()).isEqualTo(20);
        assertThat(command.getValue()
                          .criteria()
                          .sort()).isEqualTo(ProjectListCriteria.SortField.CREATED_AT);
        assertThat(command.getValue()
                          .criteria()
                          .direction()).isEqualTo(SortDirection.DESC);
    }

    @Test
    void listPassesNormalizedFiltersAndSortingToTheService() throws Exception {
        authenticate();
        when(projects.listProjects(any())).thenReturn(new Page<>(List.of(), 0, 20, 0L));
        ArgumentCaptor<ListProjectsCommand> command = ArgumentCaptor.forClass(ListProjectsCommand.class);

        mockMvc.perform(get("/api/v1/projects")
                                               .param("search", "  packing  ")
                                               .param("status", "ACTIVE", "DISABLED")
                                               .param("permission", "WRITE", "OWNER")
                                               .param("createdFrom", "2026-01-01T02:00:00+02:00")
                                               .param("createdBefore", "2027-01-01T00:00:00Z")
                                               .param("updatedFrom", "2026-06-01T00:00:00Z")
                                               .param("sort", "name")
                                               .param("direction", "DESC"))
               .andExpect(status().isOk());

        verify(projects).listProjects(command.capture());
        ProjectListCriteria criteria = command.getValue()
                                              .criteria();
        assertThat(criteria.search()).isEqualTo("packing");
        assertThat(criteria.statuses()).containsExactlyInAnyOrder(ProjectStatus.ACTIVE, ProjectStatus.DISABLED);
        assertThat(criteria.permissions()).containsExactlyInAnyOrder(ProjectPermission.WRITE, ProjectPermission.OWNER);
        assertThat(criteria.createdAt()
                           .from()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(criteria.sort()).isEqualTo(ProjectListCriteria.SortField.NAME);
        assertThat(criteria.direction()).isEqualTo(SortDirection.DESC);
    }

    @Test
    void customSortDefaultsToAscending() throws Exception {
        authenticate();
        when(projects.listProjects(any())).thenReturn(new Page<>(List.of(), 0, 20, 0L));
        ArgumentCaptor<ListProjectsCommand> command = ArgumentCaptor.forClass(ListProjectsCommand.class);

        mockMvc.perform(get("/api/v1/projects").param("sort", "name"))
               .andExpect(status().isOk());

        verify(projects).listProjects(command.capture());
        assertThat(command.getValue()
                          .criteria()
                          .sort()).isEqualTo(ProjectListCriteria.SortField.NAME);
        assertThat(command.getValue()
                          .criteria()
                          .direction()).isEqualTo(SortDirection.ASC);
    }

    @Test
    void directionWithoutSortAppliesToCreatedAt() throws Exception {
        authenticate();
        when(projects.listProjects(any())).thenReturn(new Page<>(List.of(), 0, 20, 0L));
        ArgumentCaptor<ListProjectsCommand> command = ArgumentCaptor.forClass(ListProjectsCommand.class);

        mockMvc.perform(get("/api/v1/projects").param("direction", "ASC"))
               .andExpect(status().isOk());

        verify(projects).listProjects(command.capture());
        assertThat(command.getValue()
                          .criteria()
                          .sort()).isEqualTo(ProjectListCriteria.SortField.CREATED_AT);
        assertThat(command.getValue()
                          .criteria()
                          .direction()).isEqualTo(SortDirection.ASC);
    }

    @Test
    void listCollapsesDuplicateFiltersAndTreatsBlankSearchAsAbsent() throws Exception {
        authenticate();
        when(projects.listProjects(any())).thenReturn(new Page<>(List.of(), 0, 20, 0L));
        ArgumentCaptor<ListProjectsCommand> command = ArgumentCaptor.forClass(ListProjectsCommand.class);

        mockMvc.perform(get("/api/v1/projects")
                                               .param("search", "   ")
                                               .param("status", "ACTIVE", "ACTIVE")
                                               .param("permission", "READ", "READ"))
               .andExpect(status().isOk());

        verify(projects).listProjects(command.capture());
        assertThat(command.getValue()
                          .criteria()
                          .search()).isNull();
        assertThat(command.getValue()
                          .criteria()
                          .statuses()).containsExactly(ProjectStatus.ACTIVE);
        assertThat(command.getValue()
                          .criteria()
                          .permissions()).containsExactly(ProjectPermission.READ);
    }

    @Test
    void listRejectsInvalidSearchLengths() throws Exception {
        authenticate();

        mockMvc.perform(get("/api/v1/projects").param("search", " ab "))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects").param("search", " " + "a".repeat(101) + " "))
               .andExpect(status().isBadRequest());
    }

    @Test
    void listRejectsInvalidFiltersAndRanges() throws Exception {
        authenticate();

        mockMvc.perform(get("/api/v1/projects").param("status", "DELETED"))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects").param("createdFrom", "not-a-timestamp"))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects")
                                               .param("createdFrom", "2026-01-01T00:00:00Z")
                                               .param("createdBefore", "2026-01-01T00:00:00Z"))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects")
                                               .param("updatedFrom", "2026-01-02T00:00:00Z")
                                               .param("updatedBefore", "2026-01-01T00:00:00Z"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void listRejectsUnknownQueryValues() throws Exception {
        authenticate();

        mockMvc.perform(get("/api/v1/projects").param("sort", "unknown"))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects").param("direction", "SIDEWAYS"))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects").param("status", "UNKNOWN"))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects").param("permission", "ADMIN"))
               .andExpect(status().isBadRequest());
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
    void getReturnsTheLeanProjectResponse() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        when(projects.getProject(any())).thenReturn(view(id, ProjectPermission.READ));

        mockMvc.perform(get("/api/v1/projects/{id}", id))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.id").value(id.toString()))
               .andExpect(jsonPath("$.members").doesNotExist());

        verify(projects).getProject(new ProjectQuery(UID, id));
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
    void renameReturnsTheLeanProjectResponse() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        when(projects.renameProject(any())).thenReturn(view(id, ProjectPermission.OWNER));

        mockMvc.perform(patch("/api/v1/projects/{id}", id)
                                                          .contentType(MediaType.APPLICATION_JSON)
                                                          .content("{\"name\":\"New name\"}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name").value("Chassis packing"))
               .andExpect(jsonPath("$.members").doesNotExist());

        verify(projects).renameProject(new RenameProjectCommand(UID, id, "New name"));
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
    void addMemberReturns201AndPassesTheUserIdThrough() throws Exception {
        authenticate();
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(projects.grantAccess(any())).thenReturn(view(projectId, ProjectPermission.OWNER));

        mockMvc.perform(post("/api/v1/projects/{id}/members", projectId)
                                                                        .contentType(MediaType.APPLICATION_JSON)
                                                                        .content("{\"userId\":\"" + userId
                                                                                + "\",\"permission\":\"WRITE\"}"))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.members").doesNotExist());

        verify(projects).grantAccess(new GrantAccessCommand(
                                                            UID,
                                                            projectId,
                                                            userId,
                                                            ProjectPermission.WRITE));
    }

    @Test
    void addMemberRejectsAMissingUserId() throws Exception {
        authenticate();
        mockMvc.perform(post("/api/v1/projects/{id}/members", UUID.randomUUID())
                                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                                .content("{\"permission\":\"READ\"}"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void addMemberRejectsTheLegacyIdentifierOnlyPayloadWithoutCallingTheService() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/v1/projects/{id}/members", UUID.randomUUID())
                                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                                .content("{\"identifier\":\"bob@example.com\"}"))
               .andExpect(status().isBadRequest());

        verifyNoInteractions(projects);
    }

    @Test
    void addMemberRejectsAnUnknownPermission() throws Exception {
        authenticate();
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/projects/{id}/members", UUID.randomUUID())
                                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                                .content("{\"userId\":\"" + userId
                                                                                        + "\",\"permission\":\"GOD\"}"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void addMemberSurfacesAnUnknownUserIdAs404() throws Exception {
        authenticate();
        UUID userId = UUID.randomUUID();
        when(projects.grantAccess(any()))
                                         .thenThrow(new UserNotFoundException("No active user matches that id"));

        mockMvc.perform(post("/api/v1/projects/{id}/members", UUID.randomUUID())
                                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                                .content("{\"userId\":\"" + userId
                                                                                        + "\",\"permission\":\"READ\"}"))
               .andExpect(status().isNotFound())
               .andExpect(jsonPath("$.detail").value("No active user matches that id"));
    }

    @Test
    void addMemberSurfacesADisabledUserAs422() throws Exception {
        authenticate();
        UUID userId = UUID.randomUUID();
        when(projects.grantAccess(any()))
                                         .thenThrow(new DomainRuleViolationException(
                                                                                     "Cannot add a disabled user to a project"));

        mockMvc.perform(post("/api/v1/projects/{id}/members", UUID.randomUUID())
                                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                                .content("{\"userId\":\"" + userId
                                                                                        + "\",\"permission\":\"READ\"}"))
               .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void changingAPermissionRoutesToTheMemberOnlyUseCase() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(projects.changeAccess(any())).thenReturn(view(id, ProjectPermission.OWNER));

        mockMvc.perform(patch("/api/v1/projects/{id}/members/{userId}", id, userId)
                                                                                   .contentType(MediaType.APPLICATION_JSON)
                                                                                   .content("{\"permission\":\"READ\"}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.members").doesNotExist());

        ArgumentCaptor<ChangeAccessCommand> command = ArgumentCaptor.forClass(ChangeAccessCommand.class);
        verify(projects).changeAccess(command.capture());
        assertThat(command.getValue()
                          .projectId()).isEqualTo(id);
        assertThat(command.getValue()
                          .userId()).isEqualTo(userId);
        assertThat(command.getValue()
                          .permission()).isEqualTo(ProjectPermission.READ);
    }

    @Test
    void demotingTheLastOwnerSurfacesAs409() throws Exception {
        authenticate();
        when(projects.changeAccess(any())).thenThrow(
                                                     new ResourceConflictException("Project must keep at least one owner"));

        mockMvc.perform(patch("/api/v1/projects/{id}/members/{userId}",
                              UUID.randomUUID(),
                              UUID.randomUUID())
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
                                                                                      .when(projects)
                                                                                      .revokeAccess(any());

        mockMvc.perform(delete("/api/v1/projects/{id}/members/{userId}",
                               UUID.randomUUID(),
                               UUID.randomUUID()))
               .andExpect(status().isConflict());
    }

    @Test
    void membersReturnsAPageAndPassesNormalizedFiltersToTheService() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        ProjectMemberView memberView = new ProjectMemberView(UUID.randomUUID(),
                                                             "ada",
                                                             "Ada Lovelace",
                                                             ProjectPermission.OWNER,
                                                             NOW);
        when(projects.listProjectMembers(any())).thenReturn(new Page<>(List.of(memberView), 0, 20, 1L));
        ArgumentCaptor<ListProjectMembersQuery> command = ArgumentCaptor.forClass(ListProjectMembersQuery.class);

        mockMvc.perform(get("/api/v1/projects/{id}/members", id)
                                                                .param("search", "  ada  ")
                                                                .param("permission", "READ", "OWNER")
                                                                .param("addedFrom", "2026-01-01T00:00:00Z")
                                                                .param("addedBefore", "2027-01-01T00:00:00Z")
                                                                .param("sort", "username")
                                                                .param("direction", "DESC"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.content[0].username").value("ada"))
               .andExpect(jsonPath("$.page").value(0))
               .andExpect(jsonPath("$.totalElements").value(1));

        verify(projects).listProjectMembers(command.capture());
        ProjectMemberListCriteria criteria = command.getValue()
                                                    .criteria();
        assertThat(command.getValue()
                          .firebaseUid()).isEqualTo(UID);
        assertThat(command.getValue()
                          .projectId()).isEqualTo(id);
        assertThat(criteria.search()).isEqualTo("ada");
        assertThat(criteria.permissions()).containsExactlyInAnyOrder(ProjectPermission.READ, ProjectPermission.OWNER);
        assertThat(criteria.addedAt()
                           .from()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(criteria.addedAt()
                           .before()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(criteria.sort()).isEqualTo(ProjectMemberListCriteria.SortField.USERNAME);
        assertThat(criteria.direction()).isEqualTo(SortDirection.DESC);
    }

    @Test
    void memberListDefaultsToAddedAtAscending() throws Exception {
        authenticate();
        when(projects.listProjectMembers(any())).thenReturn(new Page<>(List.of(), 0, 20, 0L));
        ArgumentCaptor<ListProjectMembersQuery> command = ArgumentCaptor.forClass(ListProjectMembersQuery.class);

        mockMvc.perform(get("/api/v1/projects/{id}/members", UUID.randomUUID()))
               .andExpect(status().isOk());

        verify(projects).listProjectMembers(command.capture());
        assertThat(command.getValue()
                          .criteria()
                          .sort()).isEqualTo(ProjectMemberListCriteria.SortField.ADDED_AT);
        assertThat(command.getValue()
                          .criteria()
                          .direction()).isEqualTo(SortDirection.ASC);
    }

    @Test
    void memberListCustomSortDefaultsToAscendingAndDirectionAloneAppliesToAddedAt() throws Exception {
        authenticate();
        when(projects.listProjectMembers(any())).thenReturn(new Page<>(List.of(), 0, 20, 0L));
        ArgumentCaptor<ListProjectMembersQuery> command = ArgumentCaptor.forClass(ListProjectMembersQuery.class);

        mockMvc.perform(get("/api/v1/projects/{id}/members", UUID.randomUUID())
                                                                               .param("sort", "displayName"))
               .andExpect(status().isOk());
        verify(projects).listProjectMembers(command.capture());
        assertThat(command.getValue()
                          .criteria()
                          .sort()).isEqualTo(ProjectMemberListCriteria.SortField.DISPLAY_NAME);
        assertThat(command.getValue()
                          .criteria()
                          .direction()).isEqualTo(SortDirection.ASC);

        mockMvc.perform(get("/api/v1/projects/{id}/members", UUID.randomUUID())
                                                                               .param("direction", "DESC"))
               .andExpect(status().isOk());
        verify(projects, org.mockito.Mockito.times(2)).listProjectMembers(command.capture());
        assertThat(command.getAllValues()
                          .getLast()
                          .criteria()
                          .sort()).isEqualTo(ProjectMemberListCriteria.SortField.ADDED_AT);
        assertThat(command.getAllValues()
                          .getLast()
                          .criteria()
                          .direction()).isEqualTo(SortDirection.DESC);
    }

    @Test
    void memberListRejectsInvalidQueryValues() throws Exception {
        authenticate();

        mockMvc.perform(get("/api/v1/projects/{id}/members", UUID.randomUUID()).param("search", " ab "))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects/{id}/members", UUID.randomUUID())
                                                                               .param("search", " " + "a".repeat(101) + " "))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects/{id}/members", UUID.randomUUID())
                                                                               .param("addedFrom", "2026-01-01T00:00:00Z")
                                                                               .param("addedBefore", "2026-01-01T00:00:00Z"))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects/{id}/members", UUID.randomUUID()).param("permission", "ADMIN"))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects/{id}/members", UUID.randomUUID()).param("sort", "unknown"))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects/{id}/members", UUID.randomUUID()).param("direction", "SIDEWAYS"))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects/{id}/members", UUID.randomUUID()).param("page", "-1"))
               .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects/{id}/members", UUID.randomUUID()).param("size", "101"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void anUnparseableProjectIdIsAClientErrorNotAServerError() throws Exception {
        authenticate();

        mockMvc.perform(get("/api/v1/projects/not-a-uuid"))
               .andExpect(status().isBadRequest());
    }
}
