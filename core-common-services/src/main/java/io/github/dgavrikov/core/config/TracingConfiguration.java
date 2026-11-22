package io.github.dgavrikov.core.config;

import io.github.dgavrikov.core.service.tracing.TraceMicrometer;
import io.github.dgavrikov.core.service.tracing.impl.TraceMicrometerImpl;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TracingConfiguration {

    @Bean
    public TraceMicrometer spanMicrometer(
            ObservationRegistry observationRegistry,
            Tracer tracer,
            Propagator propagator) {
        return new TraceMicrometerImpl(observationRegistry, tracer, propagator);
    }
}
