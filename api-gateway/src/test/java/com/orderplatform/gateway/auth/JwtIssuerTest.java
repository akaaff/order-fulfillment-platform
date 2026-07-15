package com.orderplatform.gateway.auth;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link JwtIssuer}. Uses a freshly generated in-memory RSA key
 * pair (never touches the real infra/keys/ files) so the test is fully
 * self-contained and doesn't depend on infra/generate-jwt-keys.sh having been
 * run. The private key half is PEM-encoded exactly the way the production
 * loader expects (PKCS8, "BEGIN/END PRIVATE KEY" wrapper); the public key
 * half is kept around purely so the test can independently verify that
 * issueToken() produces a signature that actually validates, rather than one
 * that merely looks structurally like a JWT.
 */
class JwtIssuerTest {

    private static final String TEST_ISSUER = "test-issuer";
    // Duration.parse expects ISO-8601 duration syntax (see JwtIssuer's constructor),
    // not a plain number of seconds - "PT5M" is 5 minutes.
    private static final Duration TEST_TTL = Duration.parse("PT5M");

    private KeyPair keyPair;
    private Resource privateKeyResource;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        privateKeyResource = new ByteArrayResource(toPem(keyPair.getPrivate().getEncoded()).getBytes());
    }

    /**
     * PEM-encodes a raw PKCS8-encoded key the same way infra/generate-jwt-keys.sh
     * would (and the way JwtIssuer#loadPrivateKey expects to parse it back):
     * base64 body wrapped in a "BEGIN/END PRIVATE KEY" header/footer.
     */
    private static String toPem(byte[] pkcs8Encoded) {
        String base64 = Base64.getEncoder().encodeToString(pkcs8Encoded);
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void issueTokenProducesAValidlySignedRs256JwtWithExpectedClaims() throws Exception {
        JwtIssuer issuer = new JwtIssuer(privateKeyResource, TEST_ISSUER, "PT5M");

        Instant beforeIssue = Instant.now();
        String token = issuer.issueToken("cust-001");
        Instant afterIssue = Instant.now();

        SignedJWT signedJWT = SignedJWT.parse(token);

        // Header: must be RS256, matching the RSASSASigner used internally.
        assertThat(signedJWT.getHeader().getAlgorithm().getName()).isEqualTo("RS256");

        // Claims: subject and issuer must match what was configured/passed in.
        assertThat(signedJWT.getJWTClaimsSet().getSubject()).isEqualTo("cust-001");
        assertThat(signedJWT.getJWTClaimsSet().getIssuer()).isEqualTo(TEST_ISSUER);

        // issueTime should be "now" at the moment issueToken() ran. JWT NumericDate
        // claims serialize to whole seconds (no sub-second precision survives the
        // encode/decode round trip), so compare with a tolerance rather than an
        // exact isBetween - otherwise truncation alone can fail the assertion.
        Date issueTime = signedJWT.getJWTClaimsSet().getIssueTime();
        assertThat(issueTime.toInstant()).isCloseTo(beforeIssue, within(2000));
        assertThat(issueTime.toInstant()).isBeforeOrEqualTo(afterIssue.plusSeconds(1));

        // expirationTime should be issueTime + the configured TTL (within a small
        // tolerance to account for clock granularity, not a fragile exact match).
        Date expirationTime = signedJWT.getJWTClaimsSet().getExpirationTime();
        Instant expectedExpiry = issueTime.toInstant().plus(TEST_TTL);
        assertThat(expirationTime.toInstant()).isCloseTo(expectedExpiry, within(2000));

        // Signature must actually verify against the corresponding public key -
        // proves the token is genuinely signed, not just structurally shaped like one.
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        assertThat(signedJWT.verify(new RSASSAVerifier(publicKey))).isTrue();
    }

    @Test
    void ttlReturnsTheConfiguredDuration() throws Exception {
        JwtIssuer issuer = new JwtIssuer(privateKeyResource, TEST_ISSUER, "PT5M");

        assertThat(issuer.ttl()).isEqualTo(TEST_TTL);
    }

    @Test
    void constructorThrowsWhenPrivateKeyResourceIsNotValidPem() {
        Resource garbage = new ByteArrayResource("not a real PEM key".getBytes());

        assertThrows(Exception.class, () -> new JwtIssuer(garbage, TEST_ISSUER, "PT5M"));
    }

    @Test
    void constructorThrowsWhenTtlIsNotAValidIso8601Duration() {
        assertThatThrownBy(() -> new JwtIssuer(privateKeyResource, TEST_ISSUER, "not-a-duration"))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    // Small local helper mirroring AssertJ's Instant "within" tolerance helper,
    // since we're comparing Instant.isCloseTo with a millisecond Duration.
    private static org.assertj.core.data.TemporalUnitOffset within(long millis) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(millis, java.time.temporal.ChronoUnit.MILLIS);
    }
}
