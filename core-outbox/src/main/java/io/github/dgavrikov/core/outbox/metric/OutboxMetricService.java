package io.github.dgavrikov.core.outbox.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class OutboxMetricService {
    private final static Map<OutboxMetricServices, Collection<OutboxMetricCounters>> registryMaps =
            Map.of(
                    OutboxMetricServices.PUBLISHER, List.of(
                            OutboxMetricCounters.EVENT_SENDING_FAILED,
                            OutboxMetricCounters.PLUGIN_NOT_REGISTRY
                    ),
                    OutboxMetricServices.MAINTENANCE, List.of(
                            OutboxMetricCounters.CLEANUP_ERROR,
                            OutboxMetricCounters.MEMORY_QUEUE_HEAVILY_LOADED,
                            OutboxMetricCounters.MEMORY_QUEUE_IS_FULL
                    ),
                    OutboxMetricServices.COORDINATOR, List.of(
                            OutboxMetricCounters.PLUGIN_NOT_REGISTRY,
                            OutboxMetricCounters.MEMORY_QUEUE_IS_FULL
                    )
            );

    private final MeterRegistry meterRegistry;

    @PostConstruct
    private void init() {
        if (meterRegistry == null)
            return;

        for (var service : registryMaps.entrySet()) {
            for (var counter : service.getValue()) {
                Counter.builder(counter.getCounterName())
                        .description(counter.getDescription())
                        .tag(service.getKey().getServiceTag(), service.getKey().getServiceValue())
                        .register(meterRegistry);
            }
        }
    }

    public void increment(OutboxMetricServices service, OutboxMetricCounters counter) {
        if (meterRegistry == null)
            return;
        meterRegistry.counter(counter.getCounterName(), service.getServiceTag(), service.getServiceValue()).increment();
    }
}
