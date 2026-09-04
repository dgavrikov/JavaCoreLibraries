package io.github.dgavrikov.core.uap.service.uap_token;

public interface UAPTokenService {
    String getJWTByOAuth2FromUAP(String type);
}
