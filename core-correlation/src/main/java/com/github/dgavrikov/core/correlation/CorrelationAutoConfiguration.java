package com.github.dgavrikov.core.correlation;

import com.github.dgavrikov.core.correlation.config.CorrelationRestClientInterceptor;
import com.github.dgavrikov.core.correlation.config.HostInfo;
import com.github.dgavrikov.core.correlation.config.RestClientCorrelationAutoConfigurer;
import com.github.dgavrikov.core.logging.LogbookLoggingAutoConfiguration;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@AutoConfiguration
@AutoConfigureBefore(LogbookLoggingAutoConfiguration.class)
@Configuration(proxyBeanMethods = false)
public class CorrelationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @Lazy
    public HostInfo hostInfo() {
        return new HostInfo();
    }

    @Bean
    public CorrelationRestClientInterceptor correlationRestClientInterceptor(@Lazy HostInfo hostInfo) {
        return new CorrelationRestClientInterceptor(hostInfo);
    }

    @Bean
    public BeanPostProcessor restClientCorrelationBeanPostProcessor(CorrelationRestClientInterceptor interceptor) {
        return new RestClientCorrelationAutoConfigurer(interceptor);
    }
}
