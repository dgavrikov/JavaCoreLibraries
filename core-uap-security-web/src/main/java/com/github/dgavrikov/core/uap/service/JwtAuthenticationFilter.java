package com.github.dgavrikov.core.uap.service;

import com.github.dgavrikov.core.uap.converter.Jwt2AuthConverter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.util.Optional;

@Slf4j
public class JwtAuthenticationFilter extends AbstractAuthenticationProcessingFilter {
    private final Jwt2AuthConverter jwt2AuthConverter;

    public JwtAuthenticationFilter(RequestMatcher protectedEndpoint, Jwt2AuthConverter jwt2AuthConverter) {
        super(protectedEndpoint);
        this.jwt2AuthConverter = jwt2AuthConverter;
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) throws AuthenticationException, IOException, ServletException {
        return Optional.ofNullable(jwt2AuthConverter.convert(request))
                .map(getAuthenticationManager()::authenticate)
                .orElseThrow(() -> new BadCredentialsException("Authorization failed"));
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain,
                                            Authentication authResult) throws IOException, ServletException {
        SecurityContextHolder.getContext().setAuthentication(authResult);
        chain.doFilter(request, response);
    }

    @Override
    public void afterPropertiesSet() {
        // disable for override filter JwtAuthenticationFilter.
        // for example: add audit on authentication.
    }
}
