package com.packing.backend.api.file;

import com.packing.backend.core.file.FileView;
import com.packing.backend.core.notification.port.out.ErrorAlerter;
import com.packing.backend.core.file.FileApplicationService;
import com.packing.backend.core.file.FileApplicationService.DeleteFileCommand;
import com.packing.backend.core.file.FileApplicationService.ListFilesCommand;
import com.packing.backend.core.file.FileApplicationService.RenameFileCommand;
import com.packing.backend.core.file.FileApplicationService.UploadFileCommand;
import com.packing.backend.core.file.port.out.BinaryStorage;
import com.packing.backend.core.shared.ContentSource;
import com.packing.backend.core.shared.Page;
import com.packing.backend.domain.file.FileStatus;
import com.packing.backend.domain.file.ModelFormat;
import com.packing.backend.domain.file.StoredFileNotFoundException;
import com.packing.backend.domain.project.ProjectId;
import com.packing.backend.domain.project.ProjectNotFoundException;
import com.packing.backend.domain.shared.DomainRuleViolationException;
import com.packing.backend.domain.shared.PermissionDeniedException;
import com.packing.backend.domain.shared.ResourceConflictException;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Filters are disabled: the real filter chain lives in {@code :app}, so the
 * {@code SecurityContextHolder} is populated directly instead.
 */
@WebMvcTest(controllers = ProjectFileController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProjectFileControllerTest {

    private static final String UID = "firebase-uid-1";
    private static final UUID PROJECT = UUID.randomUUID();
    private static final byte[] BYTES = "solid cube".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileApplicationService files;

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

    private static FileView view(UUID id) {
        return new FileView(id, PROJECT, "bracket.stl", ModelFormat.STL, "model/stl", BYTES.length,
                "d3a15aa3cd30cc79123d6a50d2809ed794a452e67fa857bbc7ac343cbfca9971",
                FileStatus.AVAILABLE, Instant.parse("2026-07-19T10:15:30Z"));
    }

    private static MockMultipartFile part(byte[] content) {
        return new MockMultipartFile("file", "bracket.stl", "application/octet-stream", content);
    }

    @Test
    void uploadReturns201WithTheStoredFile() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        when(files.upload(any())).thenReturn(view(id));

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", PROJECT).file(part(BYTES)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.filename").value("bracket.stl"))
                .andExpect(jsonPath("$.format").value("STL"))
                .andExpect(jsonPath("$.contentType").value("model/stl"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void uploadPassesTheCallersUidAndTheOriginalFilename() throws Exception {
        authenticate();
        when(files.upload(any())).thenReturn(view(UUID.randomUUID()));
        ArgumentCaptor<UploadFileCommand> command =
                ArgumentCaptor.forClass(UploadFileCommand.class);

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", PROJECT).file(part(BYTES)))
                .andExpect(status().isCreated());

        verify(files).upload(command.capture());
        assertThat(command.getValue().firebaseUid()).isEqualTo(UID);
        assertThat(command.getValue().projectId()).isEqualTo(PROJECT);
        assertThat(command.getValue().originalFilename()).isEqualTo("bracket.stl");
        assertThat(command.getValue().declaredSizeBytes()).isEqualTo(BYTES.length);
    }

    @Test
    void aCallerWithoutAccessToTheProjectGets404NotForbidden() throws Exception {
        authenticate();
        when(files.listFiles(any()))
                .thenThrow(ProjectNotFoundException.byId(new ProjectId(PROJECT)));

        mockMvc.perform(get("/api/v1/projects/{projectId}/files", PROJECT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    @Test
    void aReadOnlyMemberUploadingGets403() throws Exception {
        authenticate();
        when(files.upload(any()))
                .thenThrow(new PermissionDeniedException("This action requires WRITE permission"));

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", PROJECT).file(part(BYTES)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void uploadingToADisabledProjectGets409() throws Exception {
        authenticate();
        when(files.upload(any()))
                .thenThrow(new ResourceConflictException("Project is disabled"));

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", PROJECT).file(part(BYTES)))
                .andExpect(status().isConflict());
    }

    @Test
    void renameReturnsTheUpdatedFile() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        when(files.renameFile(any())).thenReturn(view(id));

        mockMvc.perform(patch("/api/v1/projects/{projectId}/files/{id}", PROJECT, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"chassis.stl\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        ArgumentCaptor<RenameFileCommand> command =
                ArgumentCaptor.forClass(RenameFileCommand.class);
        verify(files).renameFile(command.capture());
        assertThat(command.getValue().projectId()).isEqualTo(PROJECT);
        assertThat(command.getValue().fileId()).isEqualTo(id);
        assertThat(command.getValue().name()).isEqualTo("chassis.stl");
    }

    @Test
    void renameRejectsABlankName() throws Exception {
        authenticate();

        mockMvc.perform(patch("/api/v1/projects/{projectId}/files/{id}", PROJECT, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void renameToADifferentFormatIs422() throws Exception {
        authenticate();
        when(files.renameFile(any())).thenThrow(new DomainRuleViolationException(
                "the extension must keep the same format"));

        mockMvc.perform(patch("/api/v1/projects/{projectId}/files/{id}", PROJECT, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"chassis.obj\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void theContentSourceCanBeOpenedTwiceAndYieldsTheSameBytes() throws Exception {
        authenticate();
        when(files.upload(any())).thenReturn(view(UUID.randomUUID()));
        ArgumentCaptor<UploadFileCommand> command =
                ArgumentCaptor.forClass(UploadFileCommand.class);

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", PROJECT).file(part(BYTES)))
                .andExpect(status().isCreated());

        verify(files).upload(command.capture());
        ContentSource source = command.getValue().content();
        try (InputStream first = source.open(); InputStream second = source.open()) {
            assertThat(first.readAllBytes()).isEqualTo(BYTES);
            assertThat(second.readAllBytes()).isEqualTo(BYTES);
        }
    }

    @Test
    void uploadRejectsAnEmptyPartBeforeReachingTheUseCase() throws Exception {
        authenticate();

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", PROJECT).file(part(new byte[0])))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void uploadSurfacesAnUnsupportedFormatAs422() throws Exception {
        authenticate();
        when(files.upload(any()))
                .thenThrow(new DomainRuleViolationException("Unsupported 3D model format 'txt'"));

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", PROJECT).file(part(BYTES)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Request rejected"));
    }

    @Test
    void listReturnsAPageOfTheCallersFiles() throws Exception {
        authenticate();
        when(files.listFiles(any()))
                .thenReturn(new Page<>(List.of(view(UUID.randomUUID())), 0, 20, 1L));

        mockMvc.perform(get("/api/v1/projects/{projectId}/files", PROJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listDefaultsToTheFirstPage() throws Exception {
        authenticate();
        when(files.listFiles(any())).thenReturn(new Page<>(List.of(), 0, 20, 0L));
        ArgumentCaptor<ListFilesCommand> command =
                ArgumentCaptor.forClass(ListFilesCommand.class);

        mockMvc.perform(get("/api/v1/projects/{projectId}/files", PROJECT)).andExpect(status().isOk());

        verify(files).listFiles(command.capture());
        assertThat(command.getValue().page()).isZero();
        assertThat(command.getValue().size()).isEqualTo(20);
    }

    @Test
    void listRejectsAPageSizeAboveTheLimit() throws Exception {
        authenticate();

        mockMvc.perform(get("/api/v1/projects/{projectId}/files", PROJECT).param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listRejectsANegativePage() throws Exception {
        authenticate();

        mockMvc.perform(get("/api/v1/projects/{projectId}/files", PROJECT).param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadRedirectsToTheTemporaryUrlAndForbidsCaching() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();
        when(files.prepareDownload(any())).thenReturn(
                new BinaryStorage.TemporaryUrl(
                        URI.create("https://acct.blob.core.windows.net/models/files/x?sig=y"),
                        Instant.parse("2026-07-19T10:20:30Z")));

        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{id}/content", PROJECT, id))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://acct.blob.core.windows.net/models/files/x?sig=y"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    void downloadOfAnUnreachableFileIs404() throws Exception {
        authenticate();
        when(files.prepareDownload(any()))
                .thenThrow(new StoredFileNotFoundException("No file with id x"));

        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{id}/content", PROJECT, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    @Test
    void anUnparseableFileIdIsAClientErrorNotAServerError() throws Exception {
        authenticate();

        mockMvc.perform(get("/api/v1/projects/" + PROJECT + "/files/not-a-uuid/content"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturns204() throws Exception {
        authenticate();
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/projects/{projectId}/files/{id}", PROJECT, id))
                .andExpect(status().isNoContent());

        ArgumentCaptor<DeleteFileCommand> command =
                ArgumentCaptor.forClass(DeleteFileCommand.class);
        verify(files).deleteFile(command.capture());
        assertThat(command.getValue().firebaseUid()).isEqualTo(UID);
        assertThat(command.getValue().projectId()).isEqualTo(PROJECT);
        assertThat(command.getValue().fileId()).isEqualTo(id);
    }

    @Test
    void deleteOfAnUnreachableFileIs404() throws Exception {
        authenticate();
        org.mockito.Mockito.doThrow(new StoredFileNotFoundException("No file with id x"))
                .when(files).deleteFile(any());

        mockMvc.perform(delete("/api/v1/projects/{projectId}/files/{id}", PROJECT, UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
