package io.github.dgavrikov.core.uap.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@EqualsAndHashCode(callSuper = true)
public class AuthenticationToken extends UsernamePasswordAuthenticationToken {
    public AuthenticationToken(Object principal, Object credentials) {
        super(principal, credentials);
    }

    @Getter
    @Setter
    private String clientSessionId;

    @Getter
    @Setter
    private String userSessionId;

    @Getter
    @Setter
    private String subject;
}
