package io.github.dgavrikov.core.uap.auth.sync.service.verify;

import com.auth0.jwt.interfaces.DecodedJWT;

public interface TokenVerificationService {
    Boolean validateByAntiReplay(String tokenId);
    Boolean verifyByPublicKey(DecodedJWT jwt);
}
