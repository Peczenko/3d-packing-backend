package com.packing.backend.api.packing;

import com.packing.backend.core.notification.port.out.ErrorAlerter;
import com.packing.backend.core.packing.PackingJobApplicationService;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobResultQuery;
import com.packing.backend.core.packing.port.out.PackingJobArtifactStore;
import com.packing.backend.domain.packing.PackingJobNotFoundException;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.domain.shared.ResourceConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PackingJobResultController.class)
@AutoConfigureMockMvc(addFilters = false)
class PackingJobResultControllerTest {

    private static final String UID        = "firebase-uid-1";
    private static final UUID   PROJECT_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PackingJobApplicationService jobs;

    @MockitoBean
    private ErrorAlerter alerter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resultRedirectsToTheTemporaryUrlAndForbidsCaching() throws Exception {
        authenticate();
        UUID jobId = UUID.randomUUID();
        when(jobs.prepareResultDownload(any())).thenReturn(new PackingJobArtifactStore.TemporaryUrl(
                                                                                                    URI.create("https://storage.example/packing-jobs/result?sig=secret"),
                                                                                                    Instant.parse("2026-08-01T10:25:30Z")));

        mockMvc.perform(get("/api/v1/projects/{projectId}/packing-jobs/{jobId}/result", PROJECT_ID, jobId))
               .andExpect(status().isFound())
               .andExpect(header().string("Location",
                                          "https://storage.example/packing-jobs/result?sig=secret"))
               .andExpect(header().string("Cache-Control", "no-store"))
               .andExpect(header().string("Pragma", "no-cache"))
               .andExpect(content().string(""));

        ArgumentCaptor<PackingJobResultQuery> query = ArgumentCaptor.forClass(PackingJobResultQuery.class);
        verify(jobs).prepareResultDownload(query.capture());
        assertThat(query.getValue()
                        .firebaseUid()).isEqualTo(UID);
        assertThat(query.getValue()
                        .projectId()).isEqualTo(PROJECT_ID);
        assertThat(query.getValue()
                        .jobId()).isEqualTo(jobId);
    }

    @Test
    void unavailableResultIsAConflict() throws Exception {
        authenticate();
        when(jobs.prepareResultDownload(any()))
                                               .thenThrow(new ResourceConflictException("Packing job result is not available"));

        mockMvc.perform(get("/api/v1/projects/{projectId}/packing-jobs/{jobId}/result",
                            PROJECT_ID,
                            UUID.randomUUID()))
               .andExpect(status().isConflict());
    }

    @Test
    void unknownOrUnreachableJobIsNotFound() throws Exception {
        authenticate();
        when(jobs.prepareResultDownload(any()))
                                               .thenThrow(PackingJobNotFoundException.byId(new PackingJobId(UUID.randomUUID())));

        mockMvc.perform(get("/api/v1/projects/{projectId}/packing-jobs/{jobId}/result",
                            PROJECT_ID,
                            UUID.randomUUID()))
               .andExpect(status().isNotFound());
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
}
