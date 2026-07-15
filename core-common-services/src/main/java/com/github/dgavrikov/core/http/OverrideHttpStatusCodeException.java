package com.github.dgavrikov.core.http;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpStatusCodeException;

public class OverrideHttpStatusCodeException extends HttpStatusCodeException {
    public OverrideHttpStatusCodeException(HttpStatusCode statusCode, String statusText) {
        super(statusCode, statusText);
    }
}
