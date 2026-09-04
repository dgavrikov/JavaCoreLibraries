package io.github.dgavrikov.core.correlation.config;

public final class CorrelationHeaders {
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String MESSAGE_ID = "X-Message-Id";
    public static final String CALL_ID = "X-Call-Id";
    public static final String SESSION_ID = "X-User-Session-Id";
    public static final String INITIATOR_IP = "X-Initiator-IP";
    public static final String INITIATOR_HOST = "X-Initiator-Host";
    public static final String INITIATOR_SVC = "X-Initiator-Service";
    public static final String MDM_ID = "X-Mdm-Id";


    private CorrelationHeaders() {
    }
}
