package com.packing.backend.app.security;

import com.packing.backend.infra.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real filter chain against a real database.
 *
 * <p>The {@code jwt()} post-processor installs an already-authenticated token, so the
 * decoder and Firebase's JWKS are never contacted — what is under test is the
 * authorisation rules, the routing, and the just-in-time provisioning path end to end.
 * Token <em>verification</em> is covered by {@link FirebaseTokenValidatorTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void theApiRejectsRequestsWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    /**
     * Endpoints are protected by default: an unmapped path under /api must still challenge
     * rather than leak a 404 to an anonymous caller.
     */
    @Test
    void unknownApiPathsAlsoRequireAToken() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void aValidTokenProvisionsAProfileOnFirstCall() throws Exception {
        String uid = "firebase-uid-" + UUID.randomUUID();

        mockMvc.perform(get("/api/v1/users/me").with(firebaseUser(uid, "ROLE_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(uid + "@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void resolvingTwiceReturnsTheSameProfile() throws Exception {
        String uid = "firebase-uid-" + UUID.randomUUID();

        String first = mockMvc.perform(get("/api/v1/users/me").with(firebaseUser(uid, "ROLE_USER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/v1/users/me").with(firebaseUser(uid, "ROLE_USER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(idOf(second)).isEqualTo(idOf(first));
    }

    @Test
    void roleAssignmentIsForbiddenForOrdinaryUsers() throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}/role", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"role\":\"ADMIN\"}")
                        .with(firebaseUser("firebase-uid-plain", "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor
    firebaseUser(String uid, String authority) {
        return jwt()
                .jwt(builder -> builder
                        .subject(uid)
                        .claim("email", uid + "@example.com")
                        .claim("email_verified", true)
                        .claim("name", "Test User"))
                .authorities(new org.springframework.security.core.authority
                        .SimpleGrantedAuthority(authority));
    }

    private static String idOf(String json) {
        return com.jayway.jsonpath.JsonPath.read(json, "$.id");
    }
}
