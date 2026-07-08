package com.orderplatform.gateway.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Signs demo JWTs with the RSA private key from infra/keys/ (never committed
 * - see .gitignore). Uses Nimbus directly rather than pulling in a second
 * JWT library, since spring-boot-starter-oauth2-resource-server already
 * brings Nimbus in transitively for token *validation*.
 */
@Component
public class JwtIssuer {

    private final RSASSASigner signer;
    private final String issuer;
    private final Duration ttl;

    public JwtIssuer(
            @Value("${orderplatform.jwt.private-key-location}") Resource privateKeyResource,
            @Value("${orderplatform.jwt.issuer}") String issuer,
            @Value("${orderplatform.jwt.ttl}") String ttl
    ) throws Exception {
        this.issuer = issuer;
        this.ttl = Duration.parse(ttl);
        this.signer = new RSASSASigner(loadPrivateKey(privateKeyResource));
    }

    public String issueToken(String customerId) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(customerId)
                .issuer(issuer)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        try {
            signedJWT.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
        return signedJWT.serialize();
    }

    public Duration ttl() {
        return ttl;
    }

    private static RSAPrivateKey loadPrivateKey(Resource resource) throws Exception {
        String pem = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
    }
}
