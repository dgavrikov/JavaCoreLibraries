package io.github.dgavrikov.core.inbox.metric;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum InboxMetricServices {
    COORDINATOR("service", "coordinator"),
    MAINTENANCE("service", "maintenance"),
    PROCESSOR("service", "processor");

    private final String serviceTag;
    private final String serviceValue;
}
