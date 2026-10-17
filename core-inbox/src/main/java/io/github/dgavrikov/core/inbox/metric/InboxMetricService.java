package io.github.dgavrikov.core.inbox.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class InboxMetricService {
    private final static Map<InboxMetricServices, Collection<InboxMetricCounters>> registryMaps =
            Map.of(
                    InboxMetricServices.PROCESSOR, List.of(
                            InboxMetricCounters.EVENT_PROCESSING_FAILED,
                            InboxMetricCounters.PLUGIN_NOT_REGISTRY
                    ),
                    InboxMetricServices.MAINTENANCE, List.of(
                            InboxMetricCounters.CLEANUP_ERROR,
                            InboxMetricCounters.MEMORY_QUEUE_HEAVILY_LOADED,
                            InboxMetricCounters.MEMORY_QUEUE_IS_FULL,
                            InboxMetricCounters.PLUGIN_NOT_REGISTRY,
                            InboxMetricCounters.PAYLOAD_VALIDATION_ERROR
                    ),
                    InboxMetricServices.COORDINATOR, List.of(
                            InboxMetricCounters.MEMORY_QUEUE_IS_FULL,
                            InboxMetricCounters.PLUGIN_NOT_REGISTRY,
                            InboxMetricCounters.DUPLICATE_MESSAGE_DB_DATABASE,
                            InboxMetricCounters.PAYLOAD_VALIDATION_ERROR
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

    public void increment(InboxMetricServices service, InboxMetricCounters counter) {
        if(meterRegistry == null)
            return;
        meterRegistry.counter(counter.getCounterName(), service.getServiceTag(), service.getServiceValue()).increment();
    }
}
