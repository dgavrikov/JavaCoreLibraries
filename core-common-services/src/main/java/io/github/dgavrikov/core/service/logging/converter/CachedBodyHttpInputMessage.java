package io.github.dgavrikov.core.service.logging.converter;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class CachedBodyHttpInputMessage implements HttpInputMessage {
    private final HttpHeaders headers;
    private final byte[] cachedBytes;

    public CachedBodyHttpInputMessage(HttpInputMessage httpInputMessage) throws IOException {
        this.headers = httpInputMessage.getHeaders();

        try (InputStream inputStream = httpInputMessage.getBody();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            inputStream.transferTo(bos);
            this.cachedBytes = bos.toByteArray();
        }
    }

    @Override
    public @NonNull InputStream getBody() throws IOException {
        return new ByteArrayInputStream(this.cachedBytes);
    }

    @Override
    public @NonNull HttpHeaders getHeaders() {
        return this.headers;
    }

    public byte[] getBodyAsByteArray() {
        return this.cachedBytes;
    }
}
