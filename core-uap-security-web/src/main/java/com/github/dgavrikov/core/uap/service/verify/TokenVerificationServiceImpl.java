package com.github.dgavrikov.core.uap.service.verify;

import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.github.dgavrikov.core.uap.auth.model.TokenStatus;
import com.github.dgavrikov.core.uap.auth.sync.service.verify.TokenVerificationService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyType;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URL;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class TokenVerificationServiceImpl implements TokenVerificationService {

    @Value("${security.uap.antiReply.path}")
    private String antiReplyPath;

    @Value("${security.uap.public.key.url}")
    private String publicKeyUrl;

    @Value("${security.uap.public.key.data}")
    private String publicKeyData;

    private PublicKey publicKey;

    private final RestClient restClientAntiReply;

    @Override
    public Boolean validateByAntiReplay(String tokenId) {
        if (StringUtils.isBlank(antiReplyPath))
            return true;

        var status = restClientAntiReply.get()
                .uri(antiReplyPath, tokenId)
                .retrieve()
                .body(TokenStatus.class);
        if (status != null)
            return status.getStatus();
        return false;
    }

    @Override
    public Boolean verifyByPublicKey(DecodedJWT jwt) {
        try {
            if (publicKey == null) {
                if (!publicKeyUrl.isBlank()) {
                    var jwk = new UrlJwkProvider(new URL(publicKeyUrl)).get(jwt.getKeyId());
                    publicKey = jwk.getPublicKey();
                } else if (!publicKeyData.isBlank()) {
                    publicKey = getKey(publicKeyData, jwt.getAlgorithm());
                }
            }
            if (publicKey != null) {
                var algorithm = Algorithm.ECDSA256((ECPublicKey) publicKey, null);
                algorithm.verify(jwt);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return true;
    }

    private PublicKey getKey(String key, String alg) throws NoSuchAlgorithmException, InvalidKeySpecException {
        var publicKey = key
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replaceAll("\\n", "")
                .replace("-----END PUBLIC KEY-----", "");
        var algorithm = JWSAlgorithm.parse(alg);
        return KeyFactory
                .getInstance(KeyType.forAlgorithm(algorithm).getValue())
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
    }
}
