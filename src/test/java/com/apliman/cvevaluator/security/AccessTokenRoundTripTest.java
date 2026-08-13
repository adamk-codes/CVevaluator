package com.apliman.cvevaluator.security;

import com.apliman.cvevaluator.user.Role;
import com.apliman.cvevaluator.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A token this application mints is a token this application accepts.
 *
 * <p>The one test here that uses no MockMvc and no Spring context. Everywhere
 * else, {@link TestTokens} installs an already-verified {@code Jwt} into the
 * security context — right for testing authorization rules, but it proves
 * nothing about signing. This closes that gap: real encoder, real decoder, real
 * HMAC.
 *
 * <p>Worth having because the failure it catches is silent and total. If issuer
 * and verifier disagree, every login succeeds and every subsequent request
 * 401s, which presents as a frontend bug.
 */
class AccessTokenRoundTripTest {

    private static final String SECRET = "a-test-signing-key-that-is-long-enough-for-hs256";
    private static final String OTHER_SECRET = "a-completely-different-key-also-long-enough-x";

    @Test
    void aFreshlyIssuedTokenVerifiesAndCarriesTheIdentity() {
        SecurityConfig config = configWith(SECRET);
        AccessTokenIssuer issuer = issuerWith(SECRET, Duration.ofHours(1));

        AccessTokenIssuer.IssuedToken issued = issuer.issue(user(42L, Role.RECRUITER));
        Jwt decoded = config.jwtDecoder().decode(issued.value());

        assertThat(decoded.getSubject()).isEqualTo("42");
        assertThat(decoded.getClaimAsString(JwtClaims.ROLE)).isEqualTo("RECRUITER");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(JwtClaims.ISSUER);
        // Truncated to seconds on the way through the JWT, which stores numeric
        // dates. Compared at that resolution rather than asserting equality on
        // an Instant that carries nanoseconds the token cannot represent.
        assertThat(decoded.getExpiresAt().getEpochSecond())
                .isEqualTo(issued.expiresAt().getEpochSecond());
    }

    /**
     * The claim-to-authority mapping, which is the piece with no compile-time
     * link between its two ends: the issuer writes {@code role}, the converter
     * reads it and prefixes {@code ROLE_} so that {@code hasRole("RECRUITER")}
     * matches. Get the prefix wrong and every request authenticates and then
     * 403s — which reads as an authorization bug rather than a spelling one.
     */
    @Test
    void theRoleClaimBecomesTheAuthorityHasRoleLooksFor() {
        Jwt decoded = configWith(SECRET).jwtDecoder()
                .decode(issuerWith(SECRET, Duration.ofHours(1))
                        .issue(user(42L, Role.CANDIDATE)).value());

        assertThat(SecurityConfig.authorities(decoded))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CANDIDATE");
    }

    /** A token signed with a different key is not ours. */
    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        String foreignToken = issuerWith(OTHER_SECRET, Duration.ofHours(1))
                .issue(user(42L, Role.RECRUITER)).value();
        JwtDecoder ours = configWith(SECRET).jwtDecoder();

        assertThatThrownBy(() -> ours.decode(foreignToken)).isInstanceOf(JwtException.class);
    }

    /**
     * An expired token is refused rather than merely old.
     *
     * <p><strong>Note the five minutes.</strong> Spring's
     * {@code JwtTimestampValidator} allows 60 seconds of clock skew by default,
     * so a token that expired a moment ago is still accepted — this test
     * originally waited one second past expiry and passed the token straight
     * through. That default is not wrong (it exists so a server whose clock runs
     * slightly fast does not reject tokens a correct client just received), but
     * it does mean the effective lifetime is the configured TTL plus a minute,
     * and the number is worth knowing rather than discovering.
     *
     * <p>The token is minted through the encoder directly rather than through
     * {@link AccessTokenIssuer}, because {@link AuthProperties} corrects a
     * negative TTL and there is otherwise no way to ask for one already expired.
     */
    @Test
    void anExpiredTokenIsRejected() {
        Instant expiredFiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(5));

        String token = configWith(SECRET).jwtEncoder().encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .issuer(JwtClaims.ISSUER)
                                .issuedAt(expiredFiveMinutesAgo.minus(Duration.ofMinutes(1)))
                                .expiresAt(expiredFiveMinutesAgo)
                                .subject("42")
                                .claim(JwtClaims.ROLE, Role.RECRUITER.name())
                                .build()))
                .getTokenValue();

        assertThatThrownBy(() -> configWith(SECRET).jwtDecoder().decode(token))
                .isInstanceOf(JwtException.class);
    }

    /** The corollary, stated so the skew is pinned rather than merely noted. */
    @Test
    void aTokenExpiredWithinTheClockSkewIsStillAccepted() {
        String token = issuerWith(SECRET, Duration.ofSeconds(1))
                .issue(user(42L, Role.RECRUITER)).value();

        sleep(Duration.ofMillis(1100));

        assertThat(configWith(SECRET).jwtDecoder().decode(token).getSubject()).isEqualTo("42");
    }

    /**
     * The floor exists so that a short secret fails at startup rather than at
     * the first login, where Nimbus's {@code KeyLengthException} reads like an
     * internal error.
     */
    @Test
    void aSecretShorterThan32BytesIsRefusedAtConfigurationTime() {
        assertThatThrownBy(() -> new AuthProperties.Jwt("too-short", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    private static SecurityConfig configWith(String secret) {
        return new SecurityConfig(propertiesWith(secret, Duration.ofHours(1)));
    }

    private static AccessTokenIssuer issuerWith(String secret, Duration ttl) {
        AuthProperties properties = propertiesWith(secret, ttl);
        return new AccessTokenIssuer(new SecurityConfig(properties).jwtEncoder(), properties);
    }

    private static AuthProperties propertiesWith(String secret, Duration ttl) {
        return new AuthProperties(
                new AuthProperties.Jwt(secret, ttl),
                new AuthProperties.Cors(List.of("http://localhost:5173")));
    }

    private static User user(long id, Role role) {
        User user = new User("Someone", "hash", "someone@example.com", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
