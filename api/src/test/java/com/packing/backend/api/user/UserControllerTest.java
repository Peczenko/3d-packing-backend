package com.packing.backend.api.user;

import com.packing.backend.core.notification.port.out.ErrorAlerter;
import com.packing.backend.core.user.UserApplicationService;
import com.packing.backend.core.user.UserApplicationService.AssignUserRoleCommand;
import com.packing.backend.core.user.UserApplicationService.ResolveCurrentUserCommand;
import com.packing.backend.core.user.UserApplicationService.SearchUsersQuery;
import com.packing.backend.core.user.UserApplicationService.UpdateUserProfileCommand;
import com.packing.backend.core.user.UserSearchResult;
import com.packing.backend.core.user.UserView;
import com.packing.backend.domain.user.UserId;
import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;
import com.packing.backend.domain.user.Username;
import com.packing.backend.domain.user.UsernameAlreadyTakenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    private static final String UID = "firebase-uid-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserApplicationService users;

    @MockitoBean
    private ErrorAlerter alerter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                     .header("alg", "RS256")
                     .subject(UID)
                     .claim("email", "ada@example.com")
                     .claim("name", "Ada Lovelace")
                     .claim("email_verified", true)
                     .build();
        SecurityContextHolder.getContext()
                             .setAuthentication(
                                                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private UserView view() {
        return new UserView(UUID.randomUUID(),
                            UID,
                            "ada@example.com",
                            "ada",
                            "Ada Lovelace",
                            UserRole.USER,
                            UserStatus.ACTIVE,
                            Instant.now(),
                            Instant.now(),
                            Instant.now());
    }

    @Test
    void getMeReturnsTheResolvedProfile() throws Exception {
        authenticateAs("USER");
        when(users.resolveCurrentUser(any())).thenReturn(view());

        mockMvc.perform(get("/api/v1/users/me"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.username").value("ada"))
               .andExpect(jsonPath("$.email").value("ada@example.com"))
               .andExpect(jsonPath("$.role").value("USER"))
               // The Firebase uid is an internal linkage detail, not part of the contract.
               .andExpect(jsonPath("$.firebaseUid").doesNotExist());
    }

    @Test
    void getMePassesTheTokenClaimsToTheUseCase() throws Exception {
        authenticateAs("USER");
        when(users.resolveCurrentUser(any())).thenReturn(view());

        mockMvc.perform(get("/api/v1/users/me"))
               .andExpect(status().isOk());

        verify(users).resolveCurrentUser(
                                         new ResolveCurrentUserCommand(
                                                                       UID,
                                                                       "ada@example.com",
                                                                       "Ada Lovelace"));
    }

    @Test
    void patchMeUpdatesTheProfile() throws Exception {
        authenticateAs("USER");
        when(users.updateProfile(any())).thenReturn(view());

        mockMvc.perform(patch("/api/v1/users/me")
                                                 .contentType(MediaType.APPLICATION_JSON)
                                                 .content("{\"username\":\"ada.l\",\"displayName\":\"Ada L.\"}"))
               .andExpect(status().isOk());

        verify(users).updateProfile(
                                    new UpdateUserProfileCommand(UID, "ada.l", "Ada L."));
    }

    @Test
    void patchMeRejectsATooShortUsernameWithFieldLevelErrors() throws Exception {
        authenticateAs("USER");

        mockMvc.perform(patch("/api/v1/users/me")
                                                 .contentType(MediaType.APPLICATION_JSON)
                                                 .content("{\"username\":\"ab\"}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.title").value("Validation failed"))
               .andExpect(jsonPath("$.errors.username").exists());
    }

    @Test
    void aTakenUsernameIsReportedAsAConflict() throws Exception {
        authenticateAs("USER");
        when(users.updateProfile(any()))
                                        .thenThrow(new UsernameAlreadyTakenException(new Username("taken")));

        mockMvc.perform(patch("/api/v1/users/me")
                                                 .contentType(MediaType.APPLICATION_JSON)
                                                 .content("{\"username\":\"taken\"}"))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    void deleteMeReturnsNoContent() throws Exception {
        authenticateAs("USER");

        mockMvc.perform(delete("/api/v1/users/me"))
               .andExpect(status().isNoContent());

        verify(users).deleteAccount(UID);
    }

    @Test
    void searchReturnsCompactUsersWithoutEmailAndUsesTheDefaultLimit() throws Exception {
        UserId id = UserId.generate();
        when(users.searchUsers(new SearchUsersQuery("smith", 10)))
                                                                  .thenReturn(List.of(new UserSearchResult(
                                                                                                           id,
                                                                                                           "john_smith",
                                                                                                           "John Smith",
                                                                                                           UserStatus.DISABLED)));

        mockMvc.perform(get("/api/v1/users/search").param("pattern", "  smith  "))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].id").value(id.value()
                                                      .toString()))
               .andExpect(jsonPath("$[0].username").value("john_smith"))
               .andExpect(jsonPath("$[0].displayName").value("John Smith"))
               .andExpect(jsonPath("$[0].status").value("DISABLED"))
               .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    void searchPassesAnExplicitLimit() throws Exception {
        when(users.searchUsers(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/users/search")
                                                   .param("pattern", "ada")
                                                   .param("limit", "20"))
               .andExpect(status().isOk());

        verify(users).searchUsers(new SearchUsersQuery("ada", 20));
    }

    @Test
    void searchRejectsAPatternShorterThanThreeCharactersAfterTrimming() throws Exception {
        mockMvc.perform(get("/api/v1/users/search").param("pattern", " a "))
               .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 21 })
    void searchRejectsLimitsOutsideTheAllowedRange(int limit) throws Exception {
        mockMvc.perform(get("/api/v1/users/search")
                                                   .param("pattern", "ada")
                                                   .param("limit", Integer.toString(limit)))
               .andExpect(status().isBadRequest());
    }

    @Test
    void assigningARoleIsAllowedForAdmins() throws Exception {
        authenticateAs("ADMIN");
        when(users.assignRole(any())).thenReturn(view());
        UUID target = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/users/{id}/role", target)
                                                              .contentType(MediaType.APPLICATION_JSON)
                                                              .content("{\"role\":\"ADMIN\"}"))
               .andExpect(status().isOk());

        verify(users).assignRole(
                                 new AssignUserRoleCommand(target, UserRole.ADMIN));
    }

    @Test
    void assigningARoleIsForbiddenForOrdinaryUsers() throws Exception {
        authenticateAs("USER");

        mockMvc.perform(put("/api/v1/users/{id}/role", UUID.randomUUID())
                                                                         .contentType(MediaType.APPLICATION_JSON)
                                                                         .content("{\"role\":\"ADMIN\"}"))
               .andExpect(status().isForbidden())
               .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void malformedJsonIsABadRequestNotAServerError() throws Exception {
        authenticateAs("USER");

        mockMvc.perform(patch("/api/v1/users/me")
                                                 .contentType(MediaType.APPLICATION_JSON)
                                                 .content("{\"username\": "))
               .andExpect(status().isBadRequest());
    }

    @Test
    void anUnparseableUuidPathVariableIsABadRequestNotAServerError() throws Exception {
        authenticateAs("ADMIN");

        mockMvc.perform(put("/api/v1/users/{id}/role", "not-a-uuid")
                                                                    .contentType(MediaType.APPLICATION_JSON)
                                                                    .content("{\"role\":\"ADMIN\"}"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownEnumValueIsABadRequestNotAServerError() throws Exception {
        authenticateAs("ADMIN");

        mockMvc.perform(put("/api/v1/users/{id}/role", UUID.randomUUID())
                                                                         .contentType(MediaType.APPLICATION_JSON)
                                                                         .content("{\"role\":\"SUPERUSER\"}"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void anUnsupportedMethodIsMethodNotAllowedNotAServerError() throws Exception {
        authenticateAs("USER");

        mockMvc.perform(put("/api/v1/users/me")
                                               .contentType(MediaType.APPLICATION_JSON)
                                               .content("{}"))
               .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void aNonJwtPrincipalIsAConfigurationErrorNotABadRequest() throws Exception {
        SecurityContextHolder.getContext()
                             .setAuthentication(
                                                new UsernamePasswordAuthenticationToken("someone", "n/a", List.of()));
        when(users.resolveCurrentUser(any())).thenReturn(view());

        mockMvc.perform(get("/api/v1/users/me"))
               .andExpect(status().isInternalServerError());
    }
}
