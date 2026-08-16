package com.packing.backend.api.shared.error;

import com.packing.backend.core.notification.ServerErrorReport;
import com.packing.backend.core.notification.port.out.ErrorAlerter;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TestErrorController.class)
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

    private static final String UID = "firebase-uid-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ErrorAlerter alerter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reportsAnUnexpectedFailureAsAFiveHundredCarryingATraceId() throws Exception {
        mockMvc.perform(get("/test-errors/unexpected"))
               .andExpect(status().isInternalServerError())
               .andExpect(jsonPath("$.title").value("Internal server error"))
               .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
               .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void alertsOnAnUnexpectedFailureWithTheRequestAndTheCaller() throws Exception {
        authenticate();

        mockMvc.perform(get("/test-errors/unexpected?verbose=true"))
               .andExpect(status().isInternalServerError());

        ServerErrorReport report = capturedReport();
        assertThat(report.status()).isEqualTo(500);
        assertThat(report.httpMethod()).isEqualTo("GET");
        assertThat(report.path()).isEqualTo("/test-errors/unexpected?verbose=true");
        assertThat(report.uriTemplate()).isEqualTo("/test-errors/unexpected");
        assertThat(report.firebaseUid()).isEqualTo(UID);
        assertThat(report.userEmail()).isEqualTo("user@example.com");
        assertThat(report.roles()).contains("ROLE_USER");
        assertThat(report.cause()).isInstanceOf(IllegalStateException.class);
        assertThat(report.traceId()).isNotBlank();
        assertThat(report.occurredAt()).isNotNull();
    }

    @Test
    void reportsTheHandlerTemplateRatherThanTheConcretePath() throws Exception {
        mockMvc.perform(get("/test-errors/unexpected/8c1f"))
               .andExpect(status().isInternalServerError());

        ServerErrorReport report = capturedReport();
        assertThat(report.path()).isEqualTo("/test-errors/unexpected/8c1f");
        assertThat(report.uriTemplate()).isEqualTo("/test-errors/unexpected/{id}");
    }

    @Test
    void alertsOnADependencyFailureAndStillReturnsFiveOhThree() throws Exception {
        mockMvc.perform(get("/test-errors/dependency"))
               .andExpect(status().isServiceUnavailable())
               .andExpect(jsonPath("$.traceId").isNotEmpty());

        assertThat(capturedReport().status()).isEqualTo(503);
    }

    @Test
    void doesNotAlertOnANotFound() throws Exception {
        mockMvc.perform(get("/test-errors/missing"))
               .andExpect(status().isNotFound())
               .andExpect(jsonPath("$.traceId").doesNotExist());

        verifyNoInteractions(alerter);
    }

    @Test
    void doesNotAlertOnARejectedRequest() throws Exception {
        mockMvc.perform(get("/test-errors/rejected"))
               .andExpect(status().isUnprocessableEntity())
               .andExpect(jsonPath("$.traceId").doesNotExist());

        verifyNoInteractions(alerter);
    }

    @Test
    void doesNotAlertOnAnUnknownRoute() throws Exception {
        mockMvc.perform(get("/test-errors/no-such-thing"))
               .andExpect(status().isNotFound());

        verifyNoInteractions(alerter);
    }

    @Test
    void stillAnswersWhenAlertingItselfFails() throws Exception {
        doThrow(new IllegalStateException("alerting is broken")).when(alerter)
                                                                .alert(any());

        mockMvc.perform(get("/test-errors/unexpected"))
               .andExpect(status().isInternalServerError())
               .andExpect(jsonPath("$.title").value("Internal server error"));
    }

    private ServerErrorReport capturedReport() {
        ArgumentCaptor<ServerErrorReport> captor = ArgumentCaptor.forClass(ServerErrorReport.class);
        verify(alerter).alert(captor.capture());
        return captor.getValue();
    }

    private void authenticate() {
        Jwt jwt = Jwt.withTokenValue("token")
                     .header("alg", "RS256")
                     .subject(UID)
                     .claim("email", "user@example.com")
                     .claim("email_verified", true)
                     .build();
        SecurityContextHolder.getContext()
                             .setAuthentication(new JwtAuthenticationToken(
                                                                           jwt,
                                                                           List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
