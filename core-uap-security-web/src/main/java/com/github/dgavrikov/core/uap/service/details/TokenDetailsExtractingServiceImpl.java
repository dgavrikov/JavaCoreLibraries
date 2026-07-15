package com.github.dgavrikov.core.uap.service.details;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.github.dgavrikov.core.uap.auth.sync.service.details.TokenDetailsExtractingService;

public class TokenDetailsExtractingServiceImpl implements TokenDetailsExtractingService<Object> {
    @Override
    public Object extractDetails(DecodedJWT jwt) {
        return null;
    }
}
