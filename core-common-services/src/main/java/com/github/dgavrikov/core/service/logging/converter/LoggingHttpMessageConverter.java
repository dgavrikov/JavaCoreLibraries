package com.github.dgavrikov.core.service.logging.converter;

import com.github.dgavrikov.core.masking.MaskingMarker;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Slf4j
public record LoggingHttpMessageConverter(
        @Delegate MappingJackson2HttpMessageConverter delegate
) implements HttpMessageConverter<Object> {
    private static final long DATA_SIZE = DataSize.ofKilobytes(256L).toBytes();
    private static final Set<String> EXCLUDED_TYPE = Set.of("actuator");

    @Override
    public boolean canRead(@NonNull Class<?> clazz, @Nullable MediaType mediaType) {
        return delegate.canRead(clazz, mediaType);
    }

    @Override
    public boolean canWrite(@NonNull Class<?> clazz, @Nullable MediaType mediaType) {
        return delegate.canWrite(clazz, mediaType);
    }

    @Override
    public @NonNull List<MediaType> getSupportedMediaTypes() {
        return delegate.getSupportedMediaTypes();
    }

    @Override
    public Object read(@NonNull Class<?> clazz, @NonNull HttpInputMessage inputMessage) throws IOException, HttpMessageNotReadableException {
        Object obj;
        if (log.isDebugEnabled()) {
            var cachedInputMessage = new CachedBodyHttpInputMessage((inputMessage));
            try {
                obj = delegate.read(clazz, cachedInputMessage);
                log(obj, inputMessage);
            } catch (Exception e) {
                var body = new String(cachedInputMessage.getBodyAsByteArray(), StandardCharsets.UTF_8);
                log.debug(MaskingMarker.MASKING_JSON_ALL_MARKER, "\nError convert to Class {} Body: {}\n", clazz, body);
                throw e;
            }
        } else {
            obj = delegate.read(clazz, inputMessage);
            log(obj, inputMessage);
        }
        return obj;
    }

    @Override
    public void write(@NonNull Object object, @Nullable MediaType contentType, @NonNull HttpOutputMessage outputMessage) throws IOException, HttpMessageNotWritableException {
        var subType = contentType != null ? contentType.getSubtype() : "";

        boolean isExcluded = false;
        for (String excluded : EXCLUDED_TYPE) {
            if (subType.contains(excluded)) {
                isExcluded = true;
                break;
            }
        }

        if (!isExcluded) {
            log(object, outputMessage);
        }

        delegate.write(object, contentType, outputMessage);
    }

    private void log(Object body, HttpMessage message) {
        if (!log.isDebugEnabled())
            return;

        try {
            if (lengthOf(body) > DATA_SIZE)
                return;

            if (message instanceof HttpRequest) {
                writeLog(body, "\n>>> Body: {}\n");
            } else if (message instanceof ClientHttpResponse) {
                writeLog(body, "\n<<< Body: {}\n");
            } else if (message instanceof HttpInputMessage) {
                writeLog(body, "\n>> Body: {}\n");
            } else if (message instanceof ServletServerHttpResponse) {
                writeLog(body, "\n<< Body: {}\n");
            }
        } catch (Exception e) {
            log.warn(e.getLocalizedMessage());
        }
    }

    private void writeLog(Object body, String format) {
        log.atDebug()
                .addMarker(MaskingMarker.MASKING_OBJECT_MARKER)
                .addMarker(MaskingMarker.MASKING_JSON_MARKER)
                .addMarker(MaskingMarker.MASKING_MARKER)
                .log(format, body);
    }

    private static int lengthOf(Object o) {
        if (o == null) return 0;

        Class<?> c = o.getClass();
        if (c.isArray())
            return java.lang.reflect.Array.getLength(o);
        return switch (o) {
            case CharSequence charSequence -> charSequence.length();
            case java.util.Collection<?> objects -> objects.size();
            case java.util.Map<?, ?> maps -> maps.size();
            default -> -1;
        };
    }
}
