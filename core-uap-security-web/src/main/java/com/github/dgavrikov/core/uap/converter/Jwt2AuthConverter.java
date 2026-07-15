package com.github.dgavrikov.core.uap.converter;

import com.github.dgavrikov.core.uap.auth.model.AuthenticatedToken;
import com.github.dgavrikov.core.uap.auth.sync.filter.JwtValidationFilter;
import com.github.dgavrikov.core.uap.auth.sync.service.details.TokenDetailsExtractingService;
import com.github.dgavrikov.core.uap.auth.sync.service.verify.TokenVerificationService;
import com.github.dgavrikov.core.uap.domain.AuthenticationToken;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

@Slf4j
public class Jwt2AuthConverter implements AuthenticationConverter {
    private static final String CLIENT_SESSION_HEADER = "X-CLEINT-SESSION-ID";

    private final JwtValidationFilter jwtValidationFilter;

    public Jwt2AuthConverter(
            boolean incomingSecurityEnabled,
            TokenVerificationService tokenVerificationService,
            TokenDetailsExtractingService<?> tokenDetailsExtractingService) {
        this.jwtValidationFilter = new JwtValidationFilter(
                incomingSecurityEnabled,
                tokenDetailsExtractingService,
                tokenVerificationService);

        if(!incomingSecurityEnabled)
            log.warn("JWT token validation and UAP antiReply was disabled by toggle {security.uap.incomingSecurityEnabled}");
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        try {
            var header = request.getHeader(AUTHORIZATION);
            var token = jwtValidationFilter.attemptAuthentication(header);
            return buildAuthToken(request.getHeader(CLIENT_SESSION_HEADER), token);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Authentication buildAuthToken(String clientSessionId, AuthenticatedToken token) {
        var authToken = new AuthenticationToken(token.getSubject(), token.getPrincipal());
        authToken.setClientSessionId(clientSessionId);
        authToken.setUserSessionId(token.getPrincipal());
        authToken.setDetails(token.getDetails());
        authToken.setSubject(token.getSubject());
        return authToken;
    }
}
