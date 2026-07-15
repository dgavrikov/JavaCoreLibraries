package com.github.dgavrikov.core.config;

import com.github.dgavrikov.core.service.tracing.SpanMicrometer;
import com.github.dgavrikov.core.service.tracing.impl.SpanMicrometerImpl;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TracingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.create();
    }

    @Bean
    public SpanMicrometer spanMicrometer(ObservationRegistry observationRegistry, Tracer tracer, Propagator propagator) {
        return new SpanMicrometerImpl(observationRegistry, tracer, propagator);
    }
}
