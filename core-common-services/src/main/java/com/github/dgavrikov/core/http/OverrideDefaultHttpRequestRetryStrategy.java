package com.github.dgavrikov.core.http;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.utils.DateUtils;
import org.apache.hc.core5.concurrent.CancellableDependency;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.TimeValue;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public class OverrideDefaultHttpRequestRetryStrategy implements HttpRequestRetryStrategy {
    private final int maxRetries;
    private final TimeValue defaultRetryInterval;
    private final Set<Class<? extends IOException>> nonRetriableIOExceptionClasses;
    private final Set<Integer> retriableCodes;

    public OverrideDefaultHttpRequestRetryStrategy(
            final int maxRetries,
            final TimeValue defaultRetryInterval,
            final Collection<Class<? extends IOException>> clazzes,
            final Collection<Integer> codes) {
        Args.notNegative(maxRetries, "maxRetries");
        Args.notNegative(defaultRetryInterval.getDuration(), "defaultRetryInterval");
        this.maxRetries = maxRetries;
        this.defaultRetryInterval = defaultRetryInterval;
        this.nonRetriableIOExceptionClasses = new HashSet<>(clazzes);
        this.retriableCodes = new HashSet<>(codes);
    }

    public OverrideDefaultHttpRequestRetryStrategy(
            final int maxRetries,
            final TimeValue defaultRetryInterval,
            final Collection<Integer> codes) {
        this(maxRetries, defaultRetryInterval,
                Arrays.asList(
                        InterruptedIOException.class,
                        UnknownHostException.class,
                        ConnectException.class,
                        ConnectionClosedException.class,
                        NoRouteToHostException.class,
                        SSLException.class),
                codes);
    }

    @Override
    public boolean retryRequest(
            final HttpRequest request,
            final IOException exception,
            final int execCount,
            final HttpContext context) {
        Args.notNull(request, "request");
        Args.notNull(exception, "exception");

        if (execCount > this.maxRetries)
            return false;

        if (this.nonRetriableIOExceptionClasses.contains(exception.getClass()))
            return false;

        for (final Class<? extends IOException> rejectException : this.nonRetriableIOExceptionClasses) {
            if (rejectException.isInstance(exception))
                return false;
        }

        if (request instanceof CancellableDependency && ((CancellableDependency) request).isCancelled())
            return false;

        var retryTrue = handleAsIdempotent(request);
        if (retryTrue)
            log.warn("Retrying with exception {}", exception.getLocalizedMessage());

        return retryTrue;
    }

    @Override
    public boolean retryRequest(
            final HttpResponse response,
            final int execCount,
            final HttpContext context) {
        Args.notNull(response, "response");

        if (execCount > this.maxRetries)
            return false;

        if (retriableCodes.contains(response.getCode())) {
            log.debug("Retrying with status code {}", response.getCode());
            return true;
        }
        return false;
    }

    @Override
    public TimeValue getRetryInterval(
            final HttpResponse response,
            final int execCount,
            final HttpContext context) {
        Args.notNull(response, "response");

        final Header header = response.getFirstHeader(HttpHeaders.RETRY_AFTER);
        TimeValue retryAfter = null;
        if (header != null) {
            final String value = header.getValue();
            try {
                retryAfter = TimeValue.ofSeconds(Long.parseLong(value));
            } catch (final NumberFormatException ignore) {
                final Instant retryAfterDate = DateUtils.parseStandardDate(value);
                if (retryAfterDate != null)
                    retryAfter = TimeValue.ofMilliseconds(retryAfterDate.toEpochMilli() - System.currentTimeMillis());
            }
            if (TimeValue.isPositive(retryAfter))
                return retryAfter;
        }
        return this.defaultRetryInterval;
    }

    protected boolean handleAsIdempotent(final HttpRequest request) {
        return Method.isIdempotent(request.getMethod());
    }
}
