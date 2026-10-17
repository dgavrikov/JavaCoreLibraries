package io.github.dgavrikov.core.outbox.metric;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum OutboxMetricServices {
    MAINTENANCE("service", "maintenance"),
    PUBLISHER("service", "publisher"),
    COORDINATOR("service", "coordinator");

    private final String serviceTag;
    private final String serviceValue;
}
