package com.github.dgavrikov.core.correlation.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.Map;

public class CorrelationRestClientInterceptor implements ClientHttpRequestInterceptor {
    private final HostInfo hostInfo;

    public CorrelationRestClientInterceptor(HostInfo hostInfo) {
        this.hostInfo = hostInfo;
    }

    @Override
    public @NonNull ClientHttpResponse intercept(@NonNull HttpRequest request, byte @NonNull [] body, @NonNull ClientHttpRequestExecution execution) throws IOException {
        // 1. Add data for the current initiator host
        addHeader(request, CorrelationHeaders.INITIATOR_IP, hostInfo.localIp());
        addHeader(request, CorrelationHeaders.INITIATOR_HOST, hostInfo.localHostname());
        addHeader(request, CorrelationHeaders.INITIATOR_SVC, hostInfo.serviceName());

        // 2. DYNAMIC FORWARDING: We take everything currently in the MDC and move it into HTTP headers.
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        if (mdcContext != null) {
            mapMdcToHeader(request, mdcContext, CorrelationMdcKeys.CORRELATION_ID, CorrelationHeaders.CORRELATION_ID);
            mapMdcToHeader(request, mdcContext, CorrelationMdcKeys.MESSAGE_ID, CorrelationHeaders.MESSAGE_ID);
            mapMdcToHeader(request, mdcContext, CorrelationMdcKeys.CALL_ID, CorrelationHeaders.CALL_ID);
            mapMdcToHeader(request, mdcContext, CorrelationMdcKeys.SESSION_ID, CorrelationHeaders.SESSION_ID);
            mapMdcToHeader(request, mdcContext, CorrelationMdcKeys.MDM_ID, CorrelationHeaders.MDM_ID);
        }

        return execution.execute(request, body);
    }

    private void mapMdcToHeader(HttpRequest request, Map<String, String> mdc, String mdcKey, String headerName) {
        String value = mdc.get(mdcKey);
        if (value != null && !value.isBlank()) {
            request.getHeaders().add(headerName, value);
        }
    }

    private void addHeader(HttpRequest request, String header, String value) {
        if (value != null && !value.isBlank()) {
            request.getHeaders().add(header, value);
        }
    }
}
