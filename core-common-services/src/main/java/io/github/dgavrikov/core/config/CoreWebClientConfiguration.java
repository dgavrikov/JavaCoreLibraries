package io.github.dgavrikov.core.config;

import io.github.dgavrikov.core.http.WebResponseHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CoreWebClientConfiguration {

    @Bean
    public WebResponseHandler webResponseHandler(){
        return new WebResponseHandler();
    }
}
