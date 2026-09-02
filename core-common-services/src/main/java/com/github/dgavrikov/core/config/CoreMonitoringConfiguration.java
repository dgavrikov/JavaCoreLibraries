package com.github.dgavrikov.core.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CoreMonitoringConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.aspectj.lang.ProceedingJoinPoint")
    public static class TimeAspectConfiguration {
        @Bean
        @ConditionalOnBean(MeterRegistry.class)
        public TimedAspect timedAspect(MeterRegistry registry) {
            return new TimedAspect(registry);
        }
    }
}
