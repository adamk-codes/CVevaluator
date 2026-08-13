package com.apliman.cvevaluator.security;

import com.apliman.cvevaluator.user.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Mints the bearer token a client presents on every subsequent request.
 *
 * <p>The counterpart to the {@code JwtDecoder} in {@link SecurityConfig}: both
 * are built from the same symmetric key, which is what makes this application
 * able to verify what it signed without any external party.
 */
@Component
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final Duration ttl;

    public AccessTokenIssuer(JwtEncoder encoder, AuthProperties properties) {
        this.encoder = encoder;
        this.ttl = properties.jwt().ttl();
    }

    /**
     * A signed token identifying this user, valid for
     * {@code cvevaluator.auth.jwt.ttl}.
     *
     * <p>The role is baked into the token rather than looked up per request.
     * That is the trade the stateless choice makes and it is worth naming out
     * loud: a user whose role is changed in the database keeps their old role
     * until their current token expires, because nothing reads {@code users}
     * on an authenticated request. With one role per user and no admin screen
     * to change it, that window is theoretical here — but it is the reason a
     * role change would need a forced re-login if one ever existed.
     */
    public IssuedToken issue(User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(ttl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtClaims.ISSUER)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                // String, because "sub" is a string claim by specification and
                // Nimbus will reject a Long. JwtCurrentUserProvider parses it
                // back, and that parse is the one place the id becomes a Long
                // again.
                .subject(String.valueOf(user.getId()))
                .claim(JwtClaims.ROLE, user.getRole().name())
                .claim(JwtClaims.EMAIL, user.getEmail())
                .build();

        // The header is set explicitly. NimbusJwtEncoder defaults to RS256 when
        // no JwsHeader is given, and an RS256 header over an HMAC key fails at
        // encode time with a message about missing RSA keys that says nothing
        // about the real cause.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    /** A token and the instant it stops being accepted. */
    public record IssuedToken(String value, Instant expiresAt) {
    }
}
