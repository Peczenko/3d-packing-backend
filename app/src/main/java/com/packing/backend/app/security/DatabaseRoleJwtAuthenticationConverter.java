package com.packing.backend.app.security;

import com.packing.backend.core.user.port.in.LoadUserAuthorizationUseCase;
import com.packing.backend.core.user.port.in.LoadUserAuthorizationUseCase.UserAuthorization;
import com.packing.backend.domain.user.UserRole;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;

/**
 * Derives authorities from the database rather than from the token.
 *
 * <p>A Firebase ID token's {@code roles} custom claim is a snapshot taken when the token
 * was minted, and Firebase's revocation API invalidates refresh tokens only — an ID token
 * already in the client's hands keeps working for up to an hour. Trusting the claim would
 * therefore let a demoted administrator keep administrative access for that hour, and a
 * deleted user keep calling the API. Reading {@code users.role} on each request makes the
 * database authoritative and closes both.
 *
 * <p>The cost is one indexed lookup per authenticated request. That is deliberate: it is
 * the same trade every session-backed authorization scheme makes, and correctness here is
 * worth more than the round trip.
 */
class DatabaseRoleJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String AUTHORITY_PREFIX = "ROLE_";

    private final LoadUserAuthorizationUseCase loadUserAuthorization;

    DatabaseRoleJwtAuthenticationConverter(LoadUserAuthorizationUseCase loadUserAuthorization) {
        this.loadUserAuthorization = loadUserAuthorization;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Optional<UserAuthorization> authorization =
                loadUserAuthorization.loadAuthorization(jwt.getSubject());

        if (authorization.isEmpty()) {
            // No local profile yet. Legitimate for a Firebase identity that has never
            // called the API: the request is authenticated, and GET /users/me will
            // provision it. Only the base role is granted, so nothing privileged is
            // reachable before a profile exists.
            return new JwtAuthenticationToken(jwt, baseAuthority());
        }

        UserAuthorization user = authorization.get();
        if (!user.isActive()) {
            // Covers both DISABLED and the DELETED tombstone. Rejecting here is what makes
            // an account deletion take effect immediately instead of when the token expires.
            throw new InvalidBearerTokenException(new OAuth2Error(
                    "invalid_token",
                    "The account for this token is " + user.status().name().toLowerCase(),
                    null).getDescription());
        }
        return new JwtAuthenticationToken(jwt, authoritiesFor(user.role()));
    }

    private List<GrantedAuthority> authoritiesFor(UserRole role) {
        return List.of(new SimpleGrantedAuthority(AUTHORITY_PREFIX + role.name()));
    }

    private List<GrantedAuthority> baseAuthority() {
        return authoritiesFor(UserRole.USER);
    }
}
