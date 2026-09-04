package io.github.dgavrikov.core.uap.service.uap_token.impl;

import io.github.dgavrikov.core.uap.service.uap_token.UAPTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class UAPTokenServiceImpl implements UAPTokenService {
    private static final Authentication ANONYMOUS_AUTH = new AnonymousAuthenticationToken(
            "anonymous", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    private static final String NO_TOKEN_ERR = "no_jwt_token_found";

    private final Map<String, String> registrationCache = new ConcurrentHashMap<>();

    private final OAuth2AuthorizedClientManager manager;
    private final List<ClientRegistration> clientRegistrations;
    private final String defaultClientRegistrationId;

    @Override
    public String getJWTByOAuth2FromUAP(String type) {
        var registrationId = registrationCache.computeIfAbsent(type, this::resolveClientRegistrationId);

        var authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(registrationId)
                .principal(ANONYMOUS_AUTH)
                .build();

        return authorizeRequest(authorizeRequest);
    }

    private String resolveClientRegistrationId(String type) {
        return clientRegistrations.stream()
                .filter(reg -> !Objects.equals(reg.getRegistrationId(), defaultClientRegistrationId))
                .filter(reg -> reg.getClientName() != null && reg.getClientName().contains(type))
                .map(ClientRegistration::getRegistrationId)
                .findFirst()
                .orElse(defaultClientRegistrationId);
    }

    private String authorizeRequest(OAuth2AuthorizeRequest request) {
        var client = manager.authorize(request);
        if(client == null || client.getAccessToken() == null)
            throw new OAuth2AuthenticationException(new OAuth2Error(NO_TOKEN_ERR));
        return client.getAccessToken().getTokenValue();
    }
}
