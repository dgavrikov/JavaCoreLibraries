package com.github.dgavrikov.core.uap.auth.sync.filter;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.github.dgavrikov.core.uap.auth.exception.UapBadCredentialsException;
import com.github.dgavrikov.core.uap.auth.model.AuthenticatedToken;
import com.github.dgavrikov.core.uap.auth.sync.service.details.TokenDetailsExtractingService;
import com.github.dgavrikov.core.uap.auth.sync.service.verify.TokenVerificationService;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Optional;

public class JwtValidationFilter {
    public static final String BEARER_TOKEN = "Bearer ";

    private static final String USER_CONTEXT_ID = "ctxi";
    private static final String CONTEXT_ID_STUB = "context-id-stub";
    private static final String SUBJECT_STUB = "subject-stub";

    private final boolean uapEnabled;
    private final TokenDetailsExtractingService<?> tokenDetailsExtractingService;
    private final TokenVerificationService tokenVerificationService;

    private static final Logger log = LoggerFactory.getLogger(JwtValidationFilter.class);

    public JwtValidationFilter(boolean uapEnabled,
                               TokenDetailsExtractingService<?> tokenDetailsExtractingService,
                               TokenVerificationService tokenVerificationService) {
        this.uapEnabled = uapEnabled;
        this.tokenDetailsExtractingService = tokenDetailsExtractingService;
        this.tokenVerificationService = tokenVerificationService;

        if (!uapEnabled)
            log.warn("JWT token validation and UAP antiReply was disabled by toggle {security.uap.enabled}");
    }

    public AuthenticatedToken attemptAuthentication(String authorization) throws UapBadCredentialsException {
        return Optional.ofNullable(authorization)
                .filter(StringUtils::isNotBlank)
                .filter(token -> token.startsWith(BEARER_TOKEN))
                .map(token -> token.replace(BEARER_TOKEN, StringUtils.EMPTY))
                .map(this::validateAuthToken)
                .or(this::createEmptyAuthenticationIfNoTokenAndSecurityDisabled)
                .orElseThrow(() -> new UapBadCredentialsException("Invalid JWT  token"));
    }

    private Optional<AuthenticatedToken> createEmptyAuthenticationIfNoTokenAndSecurityDisabled() {
        if (!uapEnabled)
            return Optional.of(buildAuthToken(null));
        return Optional.empty();
    }

    private AuthenticatedToken validateAuthToken(String token) {
        DecodedJWT jwt = JWT.decode(token);
        return validateByRFC7519(jwt);
    }

    /**
     * Check using the RFС7519 algorithm
     *
     * @param jwt Token JWT
     * @return
     */
    private AuthenticatedToken validateByRFC7519(DecodedJWT jwt) {
        if (!uapEnabled)
            return buildAuthToken(jwt);
        String algorithm = jwt.getAlgorithm();
        if (algorithm == null || "none".equals(algorithm)) {
            log.error("Token algorithm not found");
            return null;
        }

        var now = new Date();
        var issueDate = jwt.getIssuedAt();
        if (issueDate == null || !DateUtils.isSameDay(issueDate, now)) {
            log.error("Token issued at: {}, now: {}", issueDate, now);
            return null;
        }

        var expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || expiresAt.before(now)) {
            log.error("Token was expired at: {}, now: {}", expiresAt, now);
            return null;
        }

        var issuer = jwt.getIssuer();
        if (issuer == null) {
            log.error("Token issuer not found");
            return null;
        }

        var subject = jwt.getSubject();
        if (subject == null) {
            log.error("Token subject not found");
            return null;
        }

        var id = jwt.getId();
        if (BooleanUtils.isFalse(checkByAntiReply(id))) {
            log.error("Token has already been deactivated");
            return null;
        }

        return buildAuthToken(jwt);
    }

    private AuthenticatedToken buildAuthToken(DecodedJWT jwt) {
        if (jwt == null)
            return new AuthenticatedToken(SUBJECT_STUB, CONTEXT_ID_STUB);

        var token = new AuthenticatedToken(jwt.getSubject(), getContextId(jwt));
        token.setDetails(tokenDetailsExtractingService.extractDetails(jwt));
        return token;
    }

    private String getContextId(DecodedJWT jwt) {
        return Optional.ofNullable(jwt.getClaim(USER_CONTEXT_ID))
                .map(Claim::asString)
                .orElse(null);
    }

    private Boolean checkByAntiReply(String id) {
        try {
            return tokenVerificationService.validateByAntiReplay(id);
        } catch (Exception e) {
            log.error("Error while UAP antiReplay checking for: " + id, e);
            return false;
        }
    }
}
