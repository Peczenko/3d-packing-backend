package com.packing.backend.app.security;

import com.packing.backend.core.user.port.in.LoadUserAuthorizationUseCase;
import com.packing.backend.core.user.port.in.LoadUserAuthorizationUseCase.UserAuthorization;
import com.packing.backend.domain.user.UserRole;
import com.packing.backend.domain.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseRoleJwtAuthenticationConverterTest {

    private static final String UID = "firebase-uid-1";

    private LoadUserAuthorizationUseCase returning(UserAuthorization authorization) {
        return firebaseUid -> Optional.ofNullable(authorization);
    }

    private Jwt tokenClaimingAdmin() {
        return Jwt.withTokenValue("token")
                  .header("alg", "RS256")
                  .subject(UID)
                  .claim("roles", List.of("ADMIN"))
                  .build();
    }

    private List<String> authoritiesFrom(UserAuthorization authorization) {
        return new DatabaseRoleJwtAuthenticationConverter(returning(authorization))
                                                                                   .convert(tokenClaimingAdmin())
                                                                                   .getAuthorities()
                                                                                   .stream()
                                                                                   .map(GrantedAuthority::getAuthority)
                                                                                   .toList();
    }

    @Test
    void grantsTheRoleStoredInTheDatabase() {
        assertThat(authoritiesFrom(
                                   new UserAuthorization(UUID.randomUUID(), UserRole.ADMIN, UserStatus.ACTIVE)))
                                                                                                                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void ignoresAStaleAdminClaimWhenTheDatabaseSaysUser() {
        assertThat(authoritiesFrom(
                                   new UserAuthorization(UUID.randomUUID(), UserRole.USER, UserStatus.ACTIVE)))
                                                                                                               .containsExactly("ROLE_USER");
    }

    @Test
    void grantsOnlyTheBaseRoleWhenNoProfileExistsYet() {
        assertThat(authoritiesFrom(null)).containsExactly("ROLE_USER");
    }

    @Test
    void rejectsADisabledAccount() {
        assertThatThrownBy(() -> authoritiesFrom(
                                                 new UserAuthorization(UUID.randomUUID(), UserRole.USER, UserStatus.DISABLED)))
                                                                                                                               .isInstanceOf(InvalidBearerTokenException.class)
                                                                                                                               .hasMessageContaining("disabled");
    }

    @Test
    void rejectsATokenForADeletedAccount() {
        assertThatThrownBy(() -> authoritiesFrom(
                                                 new UserAuthorization(UUID.randomUUID(), UserRole.USER, UserStatus.DELETED)))
                                                                                                                              .isInstanceOf(InvalidBearerTokenException.class)
                                                                                                                              .hasMessageContaining("deleted");
    }

    @Test
    void keepsTheTokenAsThePrincipal() {
        var authentication = new DatabaseRoleJwtAuthenticationConverter(
                                                                        returning(new UserAuthorization(UUID.randomUUID(), UserRole.USER, UserStatus.ACTIVE)))
                                                                                                                                                              .convert(tokenClaimingAdmin());

        assertThat(authentication.getName()).isEqualTo(UID);
    }
}
