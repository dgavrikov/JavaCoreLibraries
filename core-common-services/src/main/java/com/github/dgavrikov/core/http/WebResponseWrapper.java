package com.github.dgavrikov.core.http;

import lombok.Builder;
import org.springframework.http.HttpHeaders;


@Builder
public record WebResponseWrapper<T>(
        boolean success,
        Integer statusCode,
        String statusMessage,
        T result,
        Object responseBody,
        HttpHeaders headers
) {
}
