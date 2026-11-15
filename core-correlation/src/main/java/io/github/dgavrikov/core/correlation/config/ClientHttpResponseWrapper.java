package io.github.dgavrikov.core.correlation.config;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;

public class ClientHttpResponseWrapper implements ClientHttpResponse {
    private final ClientHttpResponse delegate;

    public ClientHttpResponseWrapper(ClientHttpResponse delegate) {
        this.delegate = delegate;
    }

    @Override
    public @NonNull HttpStatusCode getStatusCode() throws IOException {
        return delegate.getStatusCode();
    }

    @Override
    public @NonNull String getStatusText() throws IOException {
        return delegate.getStatusText();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public @NonNull InputStream getBody() throws IOException {
        return delegate.getBody();
    }

    @Override
    public @NonNull HttpHeaders getHeaders() {
        return delegate.getHeaders();
    }
}
