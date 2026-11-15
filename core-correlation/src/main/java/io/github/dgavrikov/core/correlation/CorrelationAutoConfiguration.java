package io.github.dgavrikov.core.correlation;

import io.github.dgavrikov.core.correlation.config.*;
import io.github.dgavrikov.core.logging.LogbookLoggingAutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.*;

@AutoConfiguration
@AutoConfigureBefore(LogbookLoggingAutoConfiguration.class)
@Configuration(proxyBeanMethods = false)
public class CorrelationAutoConfiguration {
    private static final Map<String, String> BASE_MAPPING = Map.of(
            CorrelationMdcKeys.CORRELATION_ID, CorrelationHeaders.CORRELATION_ID,
            CorrelationMdcKeys.MESSAGE_ID, CorrelationHeaders.MESSAGE_ID,
            CorrelationMdcKeys.CALL_ID, CorrelationHeaders.CALL_ID,
            CorrelationMdcKeys.SESSION_ID, CorrelationHeaders.SESSION_ID,
            CorrelationMdcKeys.MDM_ID, CorrelationHeaders.MDM_ID
    );

    @Bean
    @ConditionalOnMissingBean
    @Lazy
    public HostInfo hostInfo() {
        return new HostInfo();
    }

    @Bean
    public CorrelationRestClientInterceptor correlationRestClientInterceptor(
            @Lazy HostInfo hostInfo,
            Optional<List<CustomCorrelationMapping>> customMappings) {

        Map<String, String> effectiveMap = new HashMap<>(BASE_MAPPING);

        customMappings.orElse(Collections.emptyList()).stream()
                .sorted(Comparator.comparingInt(CustomCorrelationMapping::getOrder))
                .forEach(custom -> {
                    Map<String, String> mapping = custom.getMapping();
                    if (mapping != null) {
                        effectiveMap.putAll(mapping); // Перетирает совпадающие ключи, включая БАЗУ
                    }
                });

        Map<String, String> immutableEffectiveMapping = Map.copyOf(effectiveMap);

        return new CorrelationRestClientInterceptor(hostInfo, immutableEffectiveMapping);
    }

    @Bean
    public BeanPostProcessor restClientCorrelationBeanPostProcessor(CorrelationRestClientInterceptor interceptor) {
        return new RestClientCorrelationAutoConfigurer(interceptor);
    }
}
