package com.github.dgavrikov.core.uap.auth.sync.service.details;

import com.auth0.jwt.interfaces.DecodedJWT;

/**
 * Extracting data from the JWT and storing it in authenticationContext as details.
 * @param <T>
 */
public interface TokenDetailsExtractingService<T> {
    T extractDetails(DecodedJWT jwt);
}
