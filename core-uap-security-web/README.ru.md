# Библиотека для интеграции с сервисом аутентификации и проверки прав доступа

Предоставляет настроенные шаблоны регистрации OAuth2-клиентов, механизмы получения технических JWT-токенов, а также конфигурации сетевых параметров HTTP-клиентов для безопасного межсервисного взаимодействия.

## Как подключить библиотеку

### Добавить зависимость в pom.xml

```xml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-uap-security-web</artifactId>
</dependency>
```

## Конфигурация YAML

Настройте OAuth2-провайдеры и параметры сетевых соединений в вашем файле `application.yml`:

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

# Настройки Web-клиента и HTTP-соединений
web:
  clients:
    max-in-memory-size: ${WEB_MAX_IN_MEMORY_SIZE:15}
    connection:
      timeout: ${WEB_CLIENTS_CONNECTION_TIMEOUT:30000}
      retry-count: ${WEB_CLIENTS_CONNECTION_RETRY_COUNT:3}
      retry-timeout: ${WEB_CLIENTS_CONNECTION_RETRY_TIMEOUT:2000}
```

## Пример конфигурации RestClient

Приложение считывает и маппит низкоуровневые параметры сетевых соединений с помощью следующего встроенного класса конфигурации:

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

```java
import http.io.github.dgavrikov.core.OverrideDefaultHttpRequestRetryStrategy;
import web.filter.logging.service.io.github.dgavrikov.core.WebConfig;
import config.uap.io.github.dgavrikov.core.OAuth2WebClientConfig;
import utils.io.github.dgavrikov.core.Constants;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
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

    private final static String INITIATOR_SERVICE = "ХХХХ"; // System code according to the Information System Registry

    @Value("${spring.application.name}")
    private String applicationName;

    /**
     * Для внутренних вызовов, где не требуется токен
     * @param restClientBuilder
     * @param factory
     * @return
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

    @Bean
    public RestClient baseHttpClient(
            RestClient.Builder restClientBuilder,
            ClientHttpRequestFactory factory
    ) {
        return restClientBuilder
                .requestFactory(factory)
                .messageConverters(WebConfig::logMessageReplaceConverter)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                /* Не нужно, если используете core-correlation
                .defaultHeader(Constants.X_INITIATOR_SERVICE, INITIATOR_SERVICE)
                .defaultHeader(Constants.X_INITIATOR_HOST, applicationName)
                 */
                .build();
    }

    /**
     * Для внешних вызовов, где требуется токен default
     * @param restClientBuilder
     * @param factory
     * @param uapTokenService
     * @return
     */
    @Bean
    public RestClient baseHttpClientUAP(
            RestClient.Builder restClientBuilder,
            ClientHttpRequestFactory factory,
            UAPTokenService uapTokenService
    ) {
        return restClientBuilder
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(uapTokenService.getJWTByOAuth2FromUAP("default"));
                    return execution.execute(request, body);
                })
                .messageConverters(WebConfig::logMessageReplaceConverter)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                /* Не нужно, если используете core-correlation
                .defaultHeader(Constants.X_INITIATOR_SERVICE, INITIATOR_SERVICE)
                .defaultHeader(Constants.X_INITIATOR_SERVICE, applicationName)
                 */
                .build();
    }

    /**
     * Для внешних вызовов, где требуется токен tech
     * @param restClientBuilder
     * @param factory
     * @param uapTokenService
     * @return
     */
    @Bean
    public RestClient baseHttpClientUAPTech(
            RestClient.Builder restClientBuilder,
            ClientHttpRequestFactory factory,
            UAPTokenService uapTokenService
    ) {
        return restClientBuilder
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(uapTokenService.getJWTByOAuth2FromUAP("jwt-tech"));
                    return execution.execute(request, body);
                })
                .messageConverters(WebConfig::logMessageReplaceConverter)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    public RestClient customExternalRestClient(RestClient baseHttpClientUAP) {
        var factory = new DefaultUriBuilderFactory(webClientProperties.getCustom().getBaseUrl());
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        return baseHttpClientUAP.mutate()
                .uriBuilderFactory(factory)
                .baseUrl(webClientProperties.getCustom().getBaseUrl())
                // YYYY - Target system code according to the Information System Registry
                .defaultHeader(Constants.SERVICE_TO, "YYYY")
                .build();
    }
}

```

## Как использовать механизм повторных попыток (Retries)

Библиотека поддерживает два подхода к обработке временных сетевых ошибок с помощью повторных попыток: устаревший императивный билдер и современный декларативный перехватчик на уровне фабрики запросов.

### 1. Устаревший подход: Императивный через `RetryBuilder`

> ⚠️ **Устарело (Deprecated):** Данный метод оставлен исключительно для обратной совместимости с legacy-модулями. Избегайте его использования в новом коде.

```java
import http.io.github.dgavrikov.core.RetryBuilder;
import http.io.github.dgavrikov.core.WebResponseWrapper;
import utils.io.github.dgavrikov.core.Constants;
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

### 2. Рекомендуемый подход: Декларативный через `ClientHttpRequestFactory`

Это стандартный подход для экосистемы Spring Boot 3.5. Повторные попытки прозрачно выполняются под капотом движком Apache HttpClient 5 внутри настроенной фабрики `ClientHttpRequestFactory`. Код клиента остается чистым и сфокусированным только на бизнес-логике.

```java
import http.io.github.dgavrikov.core.WebResponseHandler;
import http.io.github.dgavrikov.core.WebResponseWrapper;
import utils.io.github.dgavrikov.core.Constants;
import lombok.RequiredArgsConstructor;
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

        // Ретраи срабатывают автоматически согласно конфигурации фабрики запросов
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
            return webResponseHandler.handleError("Не удалось выполнить запрос.", e);
        }
    }
}
```
