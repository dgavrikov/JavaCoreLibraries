package com.github.dgavrikov.core.http;

import com.github.dgavrikov.core.masking.MaskingMarker;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
public class WebResponseHandler {

    public <T> WebResponseWrapper<T> mapResponse(ResponseEntity<T> response, boolean bodyRequired) {
        if (bodyRequired && !response.hasBody()) {
            throw new OverrideHttpStatusCodeException(HttpStatusCode.valueOf(500), "No body found");
        }

        return WebResponseWrapper.<T>builder()
                .success(true)
                .statusCode(response.getStatusCode().value())
                .statusMessage("success")
                .result(response.getBody())
                .headers(response.getHeaders())
                .build();
    }

    public <T> WebResponseWrapper<T> handleError(String message, Throwable throwable) {
        Optional<Throwable> rootCause = Stream.iterate(throwable, Throwable::getCause)
                .filter(element -> element.getCause() == null)
                .findFirst();

        var currentError = rootCause.orElse(throwable);
        log.error("{}: {}", message, currentError.getLocalizedMessage(), currentError);

        var builder = WebResponseWrapper.<T>builder().success(false);

        return switch (currentError) {
            case OverrideHttpStatusCodeException ex -> builder
                    .statusCode(ex.getStatusCode().value())
                    .statusMessage(StringUtils.defaultIfBlank(ex.getLocalizedMessage(), ex.getStatusText()))
                    .build();
            case HttpStatusCodeException ex -> {
                var responseBody = ex.getResponseBodyAsString();
                log.debug(MaskingMarker.MASKING_JSON_MARKER, "\n>>> Error Response Body: {}\n", responseBody);
                yield builder
                        .statusCode(ex.getStatusCode().value())
                        .statusMessage(StringUtils.defaultIfBlank(ex.getLocalizedMessage(), ex.getStatusText()))
                        .responseBody(responseBody)
                        .headers(ex.getResponseHeaders())
                        .build();
            }
            default -> builder
                    .statusCode(500)
                    .statusMessage(currentError.getLocalizedMessage())
                    .build();
        };
    }
}
