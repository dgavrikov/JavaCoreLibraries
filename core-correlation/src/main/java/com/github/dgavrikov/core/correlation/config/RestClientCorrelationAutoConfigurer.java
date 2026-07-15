package com.github.dgavrikov.core.correlation.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestClientCorrelationAutoConfigurer implements BeanPostProcessor {
    private final CorrelationRestClientInterceptor interceptor;

    @Autowired
    public RestClientCorrelationAutoConfigurer(CorrelationRestClientInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public @Nullable Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        if(bean instanceof RestClient.Builder builder) {
            return builder.requestInterceptor(interceptor);
        }
        return bean;
    }
}
