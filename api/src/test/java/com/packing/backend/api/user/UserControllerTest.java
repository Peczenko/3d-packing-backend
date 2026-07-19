package com.packing.backend.api.user;

import com.packing.backend.core.user.UserView;
import com.packing.backend.core.user.port.in.AssignUserRoleUseCase;
import com.packing.backend.core.user.port.in.DeleteUserAccountUseCase;
import com.packing.backend.core.user.port.in.ResolveCurrentUserUseCase;
import com.packing.backend.core.user.port.in.UpdateUserProfileUseCase;
import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;
import com.packing.backend.domain.user.Username;
import com.packing.backend.domain.user.UsernameAlreadyTakenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for the controller.
 *
 * <p>Filters are disabled: the real filter chain lives in {@code :app} and is not on this
 * module's classpath by design. The {@code SecurityContextHolder} is populated directly
 * instead, which also exercises {@code CurrentUserArgumentResolver} — MockMvc dispatches
 * on the calling thread, so the thread-local context is visible to the handler.
 */
@WebMvcTest(controllers = UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    private static final String UID = "firebase-uid-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResolveCurrentUserUseCase resolveCurrentUser;
    @MockitoBean
    private UpdateUserProfileUseCase updateUserProfile;
    @MockitoBean
    private DeleteUserAccountUseCase deleteUserAccount;
    @MockitoBean
    private AssignUserRoleUseCase assignUserRole;

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
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private UserView view() {
        return new UserView(UUID.randomUUID(), UID, "ada@example.com", "ada", "Ada Lovelace",
                UserRole.USER, UserStatus.ACTIVE, Instant.now(), Instant.now(), Instant.now());
    }

    @Test
    void getMeReturnsTheResolvedProfile() throws Exception {
        authenticateAs("USER");
        when(resolveCurrentUser.resolveCurrentUser(any())).thenReturn(view());

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
        when(resolveCurrentUser.resolveCurrentUser(any())).thenReturn(view());

        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isOk());

        verify(resolveCurrentUser).resolveCurrentUser(
                new ResolveCurrentUserUseCase.ResolveCurrentUserCommand(
                        UID, "ada@example.com", "Ada Lovelace"));
    }

    @Test
    void patchMeUpdatesTheProfile() throws Exception {
        authenticateAs("USER");
        when(updateUserProfile.updateProfile(any())).thenReturn(view());

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ada.l\",\"displayName\":\"Ada L.\"}"))
                .andExpect(status().isOk());

        verify(updateUserProfile).updateProfile(
                new UpdateUserProfileUseCase.UpdateUserProfileCommand(UID, "ada.l", "Ada L."));
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
        when(updateUserProfile.updateProfile(any()))
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

        mockMvc.perform(delete("/api/v1/users/me")).andExpect(status().isNoContent());

        verify(deleteUserAccount).deleteAccount(UID);
    }

    @Test
    void assigningARoleIsAllowedForAdmins() throws Exception {
        authenticateAs("ADMIN");
        when(assignUserRole.assignRole(any())).thenReturn(view());
        UUID target = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/users/{id}/role", target)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        verify(assignUserRole).assignRole(
                new AssignUserRoleUseCase.AssignUserRoleCommand(target, UserRole.ADMIN));
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

    // --- framework client errors ---------------------------------------------------
    //
    // ExceptionHandlerExceptionResolver runs before Spring's DefaultHandlerExceptionResolver,
    // so a bare @ExceptionHandler(Exception.class) intercepts all of these and reports them
    // as 500. These pin the inherited ResponseEntityExceptionHandler behaviour.

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
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", "n/a", List.of()));
        when(resolveCurrentUser.resolveCurrentUser(any())).thenReturn(view());

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isInternalServerError());
    }
}
