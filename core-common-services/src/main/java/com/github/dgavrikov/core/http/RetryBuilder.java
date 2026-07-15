package com.github.dgavrikov.core.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.classify.Classifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpStatusCodeException;

import java.net.http.HttpTimeoutException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

@Slf4j
public class RetryBuilder {
    private static final int DEFAULT_MAX_ATTEMPS = 3;
    private static final int DEFAULT_RETRY_TIME_INTERVAL = 300;

    private final Set<HttpStatusCode> httpStatusRetry;

    private int retryMaxAttempts = DEFAULT_MAX_ATTEMPS;
    private int retryTimeInterval = DEFAULT_RETRY_TIME_INTERVAL;

    public RetryBuilder() {
        this.httpStatusRetry = new HashSet<>();
    }

    public RetryBuilder withHttpStatus(HttpStatus httpStatus) {
        this.httpStatusRetry.add(httpStatus);
        return this;
    }

    public RetryBuilder withRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
        return this;
    }

    public RetryBuilder withRetryTimeInterval(int retryTimeInterval) {
        this.retryTimeInterval = retryTimeInterval;
        return this;
    }

    public RetryTemplate builder() {
        if (this.httpStatusRetry.isEmpty())
            this.httpStatusRetry.addAll(getDefault());
        return createRetryTemplate();
    }

    private RetryTemplate createRetryTemplate() {
        var retry = new RetryTemplate();
        var policy = new ExceptionClassifierRetryPolicy();
        policy.setExceptionClassifier(configureStatusCodeBaseRetryPolicy());
        retry.setRetryPolicy(policy);

        var backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(this.retryTimeInterval);
        retry.setBackOffPolicy(backOffPolicy);

        return retry;
    }

    private Classifier<Throwable, RetryPolicy> configureStatusCodeBaseRetryPolicy() {
        var simpleRetryPolicy = new SimpleRetryPolicy(1 + this.retryMaxAttempts);
        var neverRetryPolicy = new NeverRetryPolicy();

        return throwable -> {
            if (throwable instanceof OverrideHttpStatusCodeException httpStatusCodeException) {
                return getRetryPolicyForStatus(httpStatusCodeException.getStatusCode(), simpleRetryPolicy, neverRetryPolicy);
            } else if (throwable instanceof HttpStatusCodeException httpStatusCodeException) {
                return getRetryPolicyForStatus(httpStatusCodeException.getStatusCode(), simpleRetryPolicy, neverRetryPolicy);
            } else {
                Optional<Throwable> rootCause = Stream.iterate(throwable, Throwable::getCause)
                        .filter(element -> element.getCause() == null)
                        .findFirst();
                if (rootCause.orElse(null) instanceof HttpTimeoutException)
                    return getRetryPolicyForStatus(HttpStatusCode.valueOf(408), simpleRetryPolicy, neverRetryPolicy);
                if (rootCause.orElse(null) instanceof CancellationException)
                    return getRetryPolicyForStatus(HttpStatusCode.valueOf(408), simpleRetryPolicy, neverRetryPolicy);
                if (rootCause.orElse(null) instanceof TimeoutException)
                    return getRetryPolicyForStatus(HttpStatusCode.valueOf(408), simpleRetryPolicy, neverRetryPolicy);
            }
            return neverRetryPolicy;
        };
    }

    private RetryPolicy getRetryPolicyForStatus(
            HttpStatusCode httpStatusCode,
            SimpleRetryPolicy simpleRetryPolicy,
            NeverRetryPolicy neverRetryPolicy) {
        if (this.httpStatusRetry.contains(httpStatusCode)) {
            log.warn("Retrying with status code {}", httpStatusCode);
            return simpleRetryPolicy;
        }
        return neverRetryPolicy;
    }

    private Set<HttpStatusCode> getDefault() {
        return Set.of(
                HttpStatusCode.valueOf(HttpStatus.SERVICE_UNAVAILABLE.value()),
                HttpStatusCode.valueOf(HttpStatus.BAD_REQUEST.value()),
                HttpStatusCode.valueOf(HttpStatus.GATEWAY_TIMEOUT.value()),
                HttpStatusCode.valueOf(HttpStatus.TOO_MANY_REQUESTS.value()),
                HttpStatusCode.valueOf(HttpStatus.UNAUTHORIZED.value()),
                HttpStatusCode.valueOf(HttpStatus.REQUEST_TIMEOUT.value())
        );
    }
}
