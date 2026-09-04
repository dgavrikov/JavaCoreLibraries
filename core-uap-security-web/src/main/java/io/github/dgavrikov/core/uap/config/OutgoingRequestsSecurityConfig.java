package io.github.dgavrikov.core.uap.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.*;
import org.springframework.security.web.SecurityFilterChain;

@Slf4j
@EnableWebSecurity
@Import(OAuth2WebClientConfig.class)
public class OutgoingRequestsSecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.requestCache(RequestCacheConfigurer::disable)
                .exceptionHandling(eh ->
                        eh.accessDeniedHandler((request, response, accessDeniedException) ->
                    response.setStatus(HttpStatus.FORBIDDEN.value())))
                .authorizeHttpRequests(authorizeExchangeSpec ->
                        authorizeExchangeSpec
                                .requestMatchers("/**").permitAll()
                                .anyRequest().permitAll())
                .oauth2Client(Customizer.withDefaults())
                .csrf(CsrfConfigurer::disable)
                .formLogin(FormLoginConfigurer::disable)
                .httpBasic(HttpBasicConfigurer::disable)
                .logout(LogoutConfigurer::disable)
                .build();
    }
}
