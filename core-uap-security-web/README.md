# Authentication and Access Control Integration Library

Provides pre-configured OAuth2 client registration templates, JWT technical token procurement, and reactive/blocking HTTP client properties tailored for secure internal and external service communications.

## Installation

### Add Dependency to pom.xml

```xml
<dependency>
    <groupId>com.github.dgavrikov.core</groupId>
    <artifactId>core-uap-security-web</artifactId>
</dependency>
```

## YAML Configuration

Configure your OAuth2 providers and HTTP connection pool parameters in your `application.yml`:

```yaml
spring:
  security:
    oauth2:
      client:
        provider:
          default:
            token-uri: ${CLIENTS_OAUTH2_PROVIDER_TOKEN_URI:https://oauth-server/passport/oauth2/token}
          jwt-tech:
            token-uri: ${CLIENTS_OAUTH2_PROVIDER_TECH_TOKEN_URI:https://oauth-server/passport/tech/oauth2/token}
        registration:
          default:
            client-id: ${CLIENTS_OAUTH2_REGISTRATION_CLIENT_ID:myclient@domain.com}
            client-secret: ${CLIENTS_OAUTH2_REGISTRATION_CLIENT_SECRET:}
            authorization-grant-type: ${CLIENTS_OAUTH2_REGISTRATION_CLIENT_AUTHORIZATION_TYPE:client_credentials}
          jwt-tech:
            client-id: ${CLIENTS_OAUTH2_REGISTRATION_TECH_CLIENT_ID:myclient@domain.com}
            client-secret: ${CLIENTS_OAUTH2_REGISTRATION_TECH_CLIENT_SECRET:}
            authorization-grant-type: ${CLIENTS_OAUTH2_REGISTRATION_TECH_CLIENT_AUTHORIZATION_TYPE:client_credentials}

# Web Client and HTTP connection configuration
web:
  clients:
    max-in-memory-size: ${WEB_MAX_IN_MEMORY_SIZE:15}
    connection:
      timeout: ${WEB_CLIENTS_CONNECTION_TIMEOUT:30000}
      retry-count: ${WEB_CLIENTS_CONNECTION_RETRY_COUNT:3}
      retry-timeout: ${WEB_CLIENTS_CONNECTION_RETRY_TIMEOUT:2000}
```

## RestClient Configuration Example

The application resolves low-level network connection properties via the following built-in configuration mapping class:

```java
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "web.clients")
public class WebClientProperties {
    private ConnectionProperties connection = new ConnectionProperties();
    private Custom custom = new Custom();
    
    @Getter
    @Setter
    public static class ConnectionProperties {
        private Integer timeout;
        private Integer retryCount;
        private Integer retryTimeout;
    }
    
    @Getter
    @Setter
    public static class Custom {
        private String baseUrl;
    }
}
```

## RestClient Infrastructure Configuration

The module provides pre-configured, production-ready `RestClient` beans optimized for internal communication and token-secured external requests using **Apache HttpClient 5** with connection pooling and advanced retry strategies.

### Configured HTTP Infrastructure Beans
* `baseHttpClient`: Designed for internal microservice calls. Does not include authentication tokens.
* `baseHttpClientUAP`: Designed for secure external calls. Automatically injects the `default` OAuth2 client credentials JWT bearer token.
* `baseHttpClientUAPTech`: Designed for secure technical system calls. Automatically injects the `jwt-tech` OAuth2 client credentials JWT bearer token.
* `customExternalRestClient`: A mutated instance of the UAP client preset with a target base URL and destination routing headers.

```java
import com.github.dgavrikov.core.http.OverrideDefaultHttpRequestRetryStrategy;
import com.github.dgavrikov.core.service.logging.filter.web.WebConfig;
import com.github.dgavrikov.core.uap.config.OAuth2WebClientConfig;
import com.github.dgavrikov.core.utils.Constants;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import javax.net.ssl.SSLException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Arrays;

@Configuration
@RequiredArgsConstructor
@OutgoingRequestsSecurityEnabled
public class RestClientsConfig {

    private final WebClientProperties webClientProperties;

    /**
     * System identification code registered within the centralized Information System Registry.
     */
    private static final String INITIATOR_SERVICE = "BASE_SYSTEM_CODE";

    @Value("\${spring.application.name}")
    private String applicationName;

    /**
     * Builds a connection-pooled HTTP client request factory equipped with 
     * custom fail-fast non-retriable exceptions and transient network retries.
     */
    @Bean
    public ClientHttpRequestFactory httpRequestFactory() {
        var connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(100);
        connectionManager.setDefaultMaxPerRoute(20);

        var requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(webClientProperties.getConnection().getTimeout()))
                .setResponseTimeout(Timeout.ofMilliseconds(webClientProperties.getConnection().getTimeout()))
                .setConnectionKeepAlive(Timeout.ofMinutes(3));

        // Network exceptions that should instantly fail without executing retry attempts
        var errorNotRetry = Arrays.asList(
                UnknownHostException.class,
                NoRouteToHostException.class,
                SSLException.class
        );

        var retryStrategy = new OverrideDefaultHttpRequestRetryStrategy(
                webClientProperties.getConnection().getRetryCount(),
                TimeValue.ofMilliseconds(webClientProperties.getConnection().getRetryTimeout()),
                errorNotRetry,
                OAuth2WebClientConfig.RETRIABLE_STATUS_CODE
        );

        var client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setRetryStrategy(retryStrategy)
                .build();

        return new HttpComponentsClientHttpRequestFactory(client);
    }

    /**
     * Baseline RestClient optimized for internal communication (No OAuth2 Token applied).
     */
    @Bean
    public RestClient baseHttpClient(
            RestClient.Builder restClientBuilder,
            ClientHttpRequestFactory factory,
            RestClientInterceptor restClientInterceptor
    ) {
        return restClientBuilder
                .requestFactory(factory)
                .requestInterceptor(restClientInterceptor)
                .messageConverters(WebConfig::logMessageReplaceConverter)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(Constants.X_INITIATOR_SERVICE, INITIATOR_SERVICE)
                .defaultHeader(Constants.X_INITIATOR_HOST, applicationName)
                .build();
    }

    /**
     * RestClient tailored for standard external calls requiring a 'default' OAuth2 scope token.
     */
    @Bean
    public RestClient baseHttpClientUAP(
            RestClient.Builder restClientBuilder,
            ClientHttpRequestFactory factory,
            UAPTokenService uapTokenService,
            RestClientInterceptor restClientInterceptor
    ) {
        return restClientBuilder
                .requestFactory(factory)
                .requestInterceptor(restClientInterceptor)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(uapTokenService.getJWTByOAuth2FromUAP("default"));
                    return execution.execute(request, body);
                })
                .messageConverters(WebConfig::logMessageReplaceConverter)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(Constants.X_INITIATOR_SERVICE, INITIATOR_SERVICE)
                .defaultHeader(Constants.X_INITIATOR_HOST, applicationName)
                .build();
    }

    /**
     * RestClient tailored for external calls requiring a specialized 'jwt-tech' scope token.
     */
    @Bean
    public RestClient baseHttpClientUAPTech(
            RestClient.Builder restClientBuilder,
            ClientHttpRequestFactory factory,
            UAPTokenService uapTokenService,
            RestClientInterceptor restClientInterceptor
    ) {
        return restClientBuilder
                .requestFactory(factory)
                .requestInterceptor(restClientInterceptor)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(uapTokenService.getJWTByOAuth2FromUAP("jwt-tech"));
                    return execution.execute(request, body);
                })
                .messageConverters(WebConfig::logMessageReplaceConverter)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Pre-configured mutated RestClient target routing property-driven endpoints.
     */
    @Bean
    public RestClient customExternalRestClient(RestClient baseHttpClientUAP) {
        var factory = new DefaultUriBuilderFactory(webClientProperties.getCustom().getBaseUrl());
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        return baseHttpClientUAP.mutate()
                .uriBuilderFactory(factory)
                .baseUrl(webClientProperties.getCustom().getBaseUrl())
                // Target system code mapping routing requirements
                .defaultHeader(Constants.SERVICE_TO, "TARGET_SYSTEM_CODE") 
                .build();
    }
}
```

## HTTP Retry Mechanism Usage

The library supports two approaches for executing HTTP retries on transient network errors: a legacy imperative builder and a modern, seamless factory-level interceptor.

### 1. Legacy Approach: Imperative via `RetryBuilder`

> ⚠️ **Deprecated:** This method is kept strictly for backward compatibility with legacy modules. Avoid using it in new features.

```java
import com.github.dgavrikov.core.http.RetryBuilder;
import com.github.dgavrikov.core.http.WebResponseWrapper;
import com.github.dgavrikov.core.utils.Constants;
import org.springframework.web.client.RestClient;

public class CustomRestClientImpl {
    private final RestClient customExternalRestClient;
    private final WebClientProperties webClientProperties;

    public WebResponseWrapper<Object> customPost(Object request) {
        String targetUri = "/api/v1/data";
        String methodName = "customPost";

        return new RetryBuilder()
                .withRetryMaxAttempts(webClientProperties.getConnection().getRetryCount())
                .withRetryTimeInterval(webClientProperties.getConnection().getRetryTimeout())
                .builder()
                .execute(retryContext -> customExternalRestClient
                        .post()
                        .uri(targetUri)
                        .header(Constants.METHOD_NAME, methodName)
                        .body(request)
                        .retrieve()
                        .toEntity(CustomResponseDto.class));
    }
}
```

### 2. Recommended Approach: Declarative via `ClientHttpRequestFactory`

This is the standard approach for Spring Boot 3.5. Retries are managed transparently under the hood by Apache HttpClient 5 inside the configured `ClientHttpRequestFactory`. The client code remains clean and focused only on business logic.

```java
import com.github.dgavrikov.core.http.WebResponseHandler;
import com.github.dgavrikov.core.http.WebResponseWrapper;
import com.github.dgavrikov.core.utils.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@RequiredArgsConstructor
public class CustomRestClientImpl {
    private final RestClient customExternalRestClient;
    private final CustomClientRestMapper customClientRestMapper;
    private final WebResponseHandler webResponseHandler;

    public WebResponseWrapper<Object> customPost(Object dto) {
        String targetUri = "/api/v1/data";
        String methodName = "customPost";

        var request = customClientRestMapper.makeRequest(dto);

        // Retries trigger automatically based on the factory configuration
        try {
            return customClientRestMapper.mapResponse(customExternalRestClient
                    .post()
                    .uri(targetUri)
                    .header(Constants.METHOD_NAME, methodName)
                    .body(request)
                    .retrieve()
                    .toEntity(CustomResponseDto.class), dto);
        } catch (HttpStatusCodeException e) {
            return customClientRestMapper.mapResponseError(e);
        } catch (Exception e) {
            return webResponseHandler.handleError("Failed to complete your request.", e);
        }
    }
}
```
