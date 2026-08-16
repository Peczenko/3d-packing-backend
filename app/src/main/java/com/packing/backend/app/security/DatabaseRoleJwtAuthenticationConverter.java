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

class DatabaseRoleJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String AUTHORITY_PREFIX = "ROLE_";

    private final LoadUserAuthorizationUseCase loadUserAuthorization;

    DatabaseRoleJwtAuthenticationConverter(LoadUserAuthorizationUseCase loadUserAuthorization) {
        this.loadUserAuthorization = loadUserAuthorization;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Optional<UserAuthorization> authorization = loadUserAuthorization.loadAuthorization(jwt.getSubject());

        if (authorization.isEmpty()) {
            return new JwtAuthenticationToken(jwt, baseAuthority());
        }

        UserAuthorization user = authorization.get();
        if (!user.isActive()) {
            throw new InvalidBearerTokenException(new OAuth2Error(
                                                                  "invalid_token",
                                                                  "The account for this token is " + user.status()
                                                                                                         .name()
                                                                                                         .toLowerCase(),
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
