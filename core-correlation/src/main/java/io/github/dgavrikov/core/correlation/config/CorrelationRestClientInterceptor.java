package io.github.dgavrikov.core.correlation.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.Map;

public class CorrelationRestClientInterceptor implements ClientHttpRequestInterceptor {
    private final HostInfo hostInfo;
    private final Map<String, String> effectiveMapping;


    public CorrelationRestClientInterceptor(
            HostInfo hostInfo,
            Map<String, String> effectiveMapping) {
        this.hostInfo = hostInfo;
        this.effectiveMapping = effectiveMapping;
    }

    @Override
    public @NonNull ClientHttpResponse intercept(@NonNull HttpRequest request, byte @NonNull [] body, @NonNull ClientHttpRequestExecution execution) throws IOException {
        addHeader(request, CorrelationHeaders.INITIATOR_IP, hostInfo.localIp());
        addHeader(request, CorrelationHeaders.INITIATOR_HOST, hostInfo.localHostname());
        addHeader(request, CorrelationHeaders.INITIATOR_SVC, hostInfo.serviceName());

        for (Map.Entry<String, String> entry : effectiveMapping.entrySet()) {
            String mdcValue = MDC.get(entry.getKey());
            if (mdcValue != null && !mdcValue.isBlank()) {
                request.getHeaders().add(entry.getValue(), mdcValue);
            }
        }

        var response = execution.execute(request, body);

        return new ClientHttpResponseWrapper(response) {
            private HttpHeaders modifiedHeaders;

            @Override
            public @NonNull HttpHeaders getHeaders() {
                if(modifiedHeaders == null) {
                    modifiedHeaders = new HttpHeaders();
                    modifiedHeaders.putAll(super.getHeaders());
                    modifiedHeaders.add(CorrelationHeaders.PATH, request.getURI().getPath());
                }
                return modifiedHeaders;
            }
        };
    }

    private void addHeader(HttpRequest request, String header, String value) {
        if (value != null && !value.isBlank()) {
            request.getHeaders().add(header, value);
        }
    }
}
