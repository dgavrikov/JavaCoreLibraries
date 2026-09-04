package io.github.dgavrikov.core.uap.config;

import io.github.dgavrikov.core.uap.auth.sync.service.details.TokenDetailsExtractingService;
import io.github.dgavrikov.core.uap.service.details.TokenDetailsExtractingServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@SuppressWarnings("all")
public class TokenDetailsConfig {
    @Bean
    @ConditionalOnMissingBean(TokenDetailsExtractingService.class)
    TokenDetailsExtractingService<?> tokenDetailsExtractingService() {
        return new TokenDetailsExtractingServiceImpl();
    }
}
