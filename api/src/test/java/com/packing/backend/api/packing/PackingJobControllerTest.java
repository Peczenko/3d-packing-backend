package com.packing.backend.api.packing;

import com.packing.backend.core.notification.port.out.ErrorAlerter;
import com.packing.backend.core.packing.PackingJobApplicationService;
import com.packing.backend.core.packing.PackingJobApplicationService.CreatePackingJobCommand;
import com.packing.backend.core.packing.PackingJobApplicationService.ListPackingJobsQuery;
import com.packing.backend.core.packing.PackingJobApplicationService.PackingJobQuery;
import com.packing.backend.core.packing.PackingJobListCriteria;
import com.packing.backend.core.packing.PackingJobView;
import com.packing.backend.core.shared.Page;
import com.packing.backend.core.shared.SortDirection;
import com.packing.backend.domain.packing.PackingJobStatus;
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
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PackingJobController.class)
@AutoConfigureMockMvc(addFilters = false)
class PackingJobControllerTest {

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
    void createReturns202WithLocationAndQueuedJob() throws Exception {
        authenticate();
        UUID jobId = UUID.randomUUID();
        when(jobs.create(any())).thenReturn(view(jobId));

        mockMvc.perform(post("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID)
                                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                                     .content("""
                                                                                         {"maxRuntimeSeconds":60,"spec":{"testField":"value"}}
                                                                                         """))
               .andExpect(status().isAccepted())
               .andExpect(header().string("Location", containsString("/packing-jobs/")))
               .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void createDefaultsTheOmittedRuntimeTo60Seconds() throws Exception {
        authenticate();
        when(jobs.create(any())).thenReturn(view(UUID.randomUUID()));

        mockMvc.perform(post("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID)
                                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                                     .content("{" + "\"spec\":{}" + "}"))
               .andExpect(status().isAccepted());

        ArgumentCaptor<CreatePackingJobCommand> command = ArgumentCaptor.forClass(CreatePackingJobCommand.class);
        verify(jobs).create(command.capture());
        assertThat(command.getValue()
                          .maxRuntimeSeconds()).isEqualTo(60);
    }

    @Test
    void createPreservesArbitraryNestedAndArraySpecJson() throws Exception {
        authenticate();
        when(jobs.create(any())).thenReturn(view(UUID.randomUUID()));

        mockMvc.perform(post("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID)
                                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                                     .content("""
                                                                                         {"spec":{"nested":{"items":[1,{"name":"part"}]},"flags":[true,false]}}
                                                                                         """))
               .andExpect(status().isAccepted());

        ArgumentCaptor<CreatePackingJobCommand> command = ArgumentCaptor.forClass(CreatePackingJobCommand.class);
        verify(jobs).create(command.capture());
        assertThat(command.getValue()
                          .specJson()).isEqualTo(
                                                 "{\"nested\":{\"items\":[1,{\"name\":\"part\"}]},\"flags\":[true,false]}");
    }

    @Test
    void createRejectsANullSpec() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID)
                                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                                     .content("{\"spec\":null}"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsRuntimeAbove7200Seconds() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID)
                                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                                     .content("{\"maxRuntimeSeconds\":7201,\"spec\":{}}"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsRuntimeBelowOneSecond() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID)
                                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                                     .content("{\"maxRuntimeSeconds\":0,\"spec\":{}}"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void listMapsAllCriteriaAndReturnsStandardPageFields() throws Exception {
        authenticate();
        when(jobs.list(any())).thenReturn(new Page<>(List.of(view(UUID.randomUUID())), 0, 20, 1));

        mockMvc.perform(get("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID)
                                                                                    .param("search", "  engine  ")
                                                                                    .param("status", "RUNNING", "FAILED")
                                                                                    .param("createdFrom", "2026-01-01T00:00:00Z")
                                                                                    .param("createdBefore", "2027-01-01T00:00:00Z")
                                                                                    .param("startedFrom", "2026-02-01T00:00:00Z")
                                                                                    .param("startedBefore", "2026-12-01T00:00:00Z")
                                                                                    .param("finishedFrom", "2026-03-01T00:00:00Z")
                                                                                    .param("finishedBefore", "2026-11-01T00:00:00Z")
                                                                                    .param("sort", "finishedAt")
                                                                                    .param("direction", "DESC"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.content.length()").value(1))
               .andExpect(jsonPath("$.page").value(0))
               .andExpect(jsonPath("$.size").value(20))
               .andExpect(jsonPath("$.totalElements").value(1))
               .andExpect(jsonPath("$.totalPages").value(1));

        ArgumentCaptor<ListPackingJobsQuery> query = ArgumentCaptor.forClass(ListPackingJobsQuery.class);
        verify(jobs).list(query.capture());
        assertThat(query.getValue()
                        .firebaseUid()).isEqualTo(UID);
        assertThat(query.getValue()
                        .projectId()).isEqualTo(PROJECT_ID);
        assertThat(query.getValue()
                        .criteria()
                        .page()
                        .page()).isZero();
        assertThat(query.getValue()
                        .criteria()
                        .page()
                        .size()).isEqualTo(20);
        assertThat(query.getValue()
                        .criteria()
                        .search()).isEqualTo("engine");
        assertThat(query.getValue()
                        .criteria()
                        .statuses()).containsExactlyInAnyOrder(PackingJobStatus.RUNNING, PackingJobStatus.FAILED);
        assertThat(query.getValue()
                        .criteria()
                        .createdAt()).hasToString("InstantRange[from=2026-01-01T00:00:00Z, before=2027-01-01T00:00:00Z]");
        assertThat(query.getValue()
                        .criteria()
                        .startedAt()).hasToString("InstantRange[from=2026-02-01T00:00:00Z, before=2026-12-01T00:00:00Z]");
        assertThat(query.getValue()
                        .criteria()
                        .finishedAt()).hasToString("InstantRange[from=2026-03-01T00:00:00Z, before=2026-11-01T00:00:00Z]");
        assertThat(query.getValue()
                        .criteria()
                        .sort()).isEqualTo(PackingJobListCriteria.SortField.FINISHED_AT);
        assertThat(query.getValue()
                        .criteria()
                        .direction()).isEqualTo(SortDirection.DESC);
    }

    @Test
    void listDefaultsToCreatedAtDescending() throws Exception {
        authenticate();
        when(jobs.list(any())).thenReturn(new Page<>(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID))
               .andExpect(status().isOk());

        ArgumentCaptor<ListPackingJobsQuery> query = ArgumentCaptor.forClass(ListPackingJobsQuery.class);
        verify(jobs).list(query.capture());
        assertThat(query.getValue()
                        .criteria()
                        .sort()).isEqualTo(PackingJobListCriteria.SortField.CREATED_AT);
        assertThat(query.getValue()
                        .criteria()
                        .direction()).isEqualTo(SortDirection.DESC);
    }

    @Test
    void listDefaultsCustomSortDirectionToAscendingAndAcceptsEverySortField() throws Exception {
        authenticate();
        when(jobs.list(any())).thenReturn(new Page<>(List.of(), 0, 20, 0));

        List<String> sorts = List.of("status",
                                     "maxRuntimeSeconds",
                                     "engineVersion",
                                     "createdAt",
                                     "startedAt",
                                     "finishedAt",
                                     "resultFileName",
                                     "resultSizeBytes");
        for (String sort : sorts) {
            mockMvc.perform(get("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID).param("sort", sort))
                   .andExpect(status().isOk());
        }

        ArgumentCaptor<ListPackingJobsQuery> query = ArgumentCaptor.forClass(ListPackingJobsQuery.class);
        verify(jobs, org.mockito.Mockito.times(sorts.size())).list(query.capture());
        assertThat(query.getAllValues())
                                        .extracting(value -> value.criteria()
                                                                  .sort())
                                        .containsExactly(PackingJobListCriteria.SortField.STATUS,
                                                         PackingJobListCriteria.SortField.MAX_RUNTIME_SECONDS,
                                                         PackingJobListCriteria.SortField.ENGINE_VERSION,
                                                         PackingJobListCriteria.SortField.CREATED_AT,
                                                         PackingJobListCriteria.SortField.STARTED_AT,
                                                         PackingJobListCriteria.SortField.FINISHED_AT,
                                                         PackingJobListCriteria.SortField.RESULT_FILE_NAME,
                                                         PackingJobListCriteria.SortField.RESULT_SIZE_BYTES);
        assertThat(query.getAllValues())
                                        .allSatisfy(value -> assertThat(value.criteria()
                                                                             .direction()).isEqualTo(SortDirection.ASC));
    }

    @Test
    void listRejectsInvalidSearchAndRangeQueryParameters() throws Exception {
        authenticate();

        for (String search : List.of("  ab  ", "x".repeat(101))) {
            mockMvc.perform(get("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID).param("search", search))
                   .andExpect(status().isBadRequest());
        }
        for (String field : List.of("createdFrom", "createdBefore", "startedFrom", "startedBefore", "finishedFrom", "finishedBefore")) {
            mockMvc.perform(get("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID).param(field, "not-a-timestamp"))
                   .andExpect(status().isBadRequest());
        }
        for (String prefix : List.of("created", "started", "finished")) {
            mockMvc.perform(get("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID)
                                                                                        .param(prefix + "From", "2026-01-01T00:00:00Z")
                                                                                        .param(prefix + "Before", "2026-01-01T00:00:00Z"))
                   .andExpect(status().isBadRequest());
            mockMvc.perform(get("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID)
                                                                                        .param(prefix + "From", "2026-01-02T00:00:00Z")
                                                                                        .param(prefix + "Before", "2026-01-01T00:00:00Z"))
                   .andExpect(status().isBadRequest());
        }
    }

    @Test
    void listRejectsUnknownStatusSortAndDirection() throws Exception {
        authenticate();

        for (String[] parameter : List.of(new String[] { "status", "UNKNOWN" },
                                          new String[] { "sort", "unknown" },
                                          new String[] { "direction", "SIDEWAYS" })) {
            mockMvc.perform(get("/api/v1/projects/{projectId}/packing-jobs", PROJECT_ID)
                                                                                        .param(parameter[0], parameter[1]))
                   .andExpect(status().isBadRequest());
        }
    }

    @Test
    void detailPassesBothProjectAndJobIdsToCore() throws Exception {
        authenticate();
        UUID jobId = UUID.randomUUID();
        when(jobs.get(any())).thenReturn(view(jobId));

        mockMvc.perform(get("/api/v1/projects/{projectId}/packing-jobs/{jobId}", PROJECT_ID, jobId))
               .andExpect(status().isOk());

        verify(jobs).get(new PackingJobQuery(UID, PROJECT_ID, jobId));
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

    private static PackingJobView view(UUID jobId) {
        return new PackingJobView(jobId,
                                  PROJECT_ID,
                                  PackingJobStatus.QUEUED,
                                  60,
                                  null,
                                  null,
                                  Instant.parse("2026-08-01T10:15:30Z"),
                                  null,
                                  null,
                                  null,
                                  null,
                                  null,
                                  null,
                                  null);
    }
}
