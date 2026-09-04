package io.github.dgavrikov.core.uap.config;

import io.github.dgavrikov.core.http.OverrideDefaultHttpRequestRetryStrategy;
import io.github.dgavrikov.core.uap.service.uap_token.UAPTokenService;
import io.github.dgavrikov.core.uap.service.uap_token.impl.UAPTokenServiceImpl;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class OAuth2WebClientConfig {
    public static final String DEFAULT_CLIENT_REGISTRATION_ID = "default";
    public static final Collection<Integer> RETRIABLE_STATUS_CODE =  Arrays.asList(
            HttpStatus.SC_SERVICE_UNAVAILABLE,
            HttpStatus.SC_BAD_GATEWAY,
            HttpStatus.SC_GATEWAY_TIMEOUT,
            HttpStatus.SC_TOO_MANY_REQUESTS,
            HttpStatus.SC_UNAUTHORIZED,
            HttpStatus.SC_REQUEST_TIMEOUT
    );

    @Bean
    List<ClientRegistration> clientRegistrations(OAuth2ClientProperties oAuth2ClientProperties) {
        return clientRegistrationsList(oAuth2ClientProperties);
    }

    @Bean("defaultHttpRequestFactory")
    @ConditionalOnMissingBean(ClientHttpRequestFactory.class)
    public ClientHttpRequestFactory defaultHttpRequestFactory() {

        var def = new OverrideDefaultHttpRequestRetryStrategy(
                10,
                TimeValue.ofMilliseconds(200),
                RETRIABLE_STATUS_CODE);

        var client = HttpClients.custom()
                .setRetryStrategy(def)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofMinutes(1))
                .build();

        return new HttpComponentsClientHttpRequestFactory(client);
    }

    @Bean(name = "uapRestClient")
    RestClient uapRestClient(ObservationRegistry registry,
                             @Qualifier("defaultHttpRequestFactory") ClientHttpRequestFactory defaultHttpRequestFactory) {
        return RestClient.builder()
                .requestFactory(defaultHttpRequestFactory)
                .messageConverters(messageConverters -> {
                    messageConverters.clear();
                    messageConverters.add(new FormHttpMessageConverter());
                    messageConverters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .requestInterceptor((request, body, execution) -> {
                    log.info("UAP request token:\n>>> Method: {}\n>>> URI: {}\n",
                            request.getMethod(),
                            request.getURI());
                    var response = execution.execute(request, body);
                    log.info("Response from UAP:\n<<< URI: {}\n<<< Status: {}\n",
                            request.getURI(),
                            response.getStatusCode().value());
                    return response;
                })
                .observationRegistry(registry)
                .build();
    }

    OAuth2AuthorizedClientManager authorizedClientManager(List<ClientRegistration> clientRegistrations,
                                                          @Qualifier("uapRestClient") RestClient uapRestClient) {
        var clientCredentialsTokenResponseClient = new RestClientClientCredentialsTokenResponseClient();
        clientCredentialsTokenResponseClient.setRestClient(uapRestClient);
        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials(builder -> builder.accessTokenResponseClient(clientCredentialsTokenResponseClient))
                        .build();

        var clientRegistrationRepository = new InMemoryClientRegistrationRepository(clientRegistrations);
        var clientService = new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
        var authorizedClientManager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                clientRegistrationRepository, clientService);

        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);

        return authorizedClientManager;
    }

    @Bean
    public UAPTokenService uapTokenService(OAuth2AuthorizedClientManager authorizedClientManager,
                                           List<ClientRegistration> clientRegistrations) {
        return new UAPTokenServiceImpl(
                authorizedClientManager,
                clientRegistrations,
                getClientRegistrationIfConfigExists(clientRegistrations));
    }

    private static List<ClientRegistration> clientRegistrationsList(OAuth2ClientProperties oAuth2ClientProperties) {
        return oAuth2ClientProperties.getRegistration().entrySet().stream()
                .map(entry -> buildClientRegistration(entry.getKey(), entry.getValue(),
                        oAuth2ClientProperties))
                .toList();
    }

    private static ClientRegistration buildClientRegistration(String registrationKey,
                                                              OAuth2ClientProperties.Registration registrationProperties,
                                                              OAuth2ClientProperties oAuth2ClientProperties) {
        var tokenUri = oAuth2ClientProperties.getProvider().get(registrationKey).getTokenUri();
        return ClientRegistration.withRegistrationId(registrationKey)
                .tokenUri(tokenUri)
                .clientId(registrationProperties.getClientId())
                .clientSecret(registrationProperties.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(registrationProperties.getScope())
                .clientName(registrationProperties.getClientName())
                .build();
    }

    private static String getClientRegistrationIfConfigExists(List<ClientRegistration> clientRegistrations) {
        return clientRegistrations.stream()
                .filter( clientRegistration -> DEFAULT_CLIENT_REGISTRATION_ID.equals(clientRegistration.getRegistrationId()))
                .findFirst()
                .map(ClientRegistration::getRegistrationId)
                .orElse(null);
    }
}
