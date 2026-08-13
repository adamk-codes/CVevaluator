package com.apliman.cvevaluator.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Authentication configuration, bound from {@code cvevaluator.auth.*}.
 */
@ConfigurationProperties("cvevaluator.auth")
public record AuthProperties(Jwt jwt, Cors cors) {

    private static final Logger log = LoggerFactory.getLogger(AuthProperties.class);

    /**
     * The signing key a fresh clone starts with.
     *
     * <p>Committed on purpose, and therefore not a secret. The alternative —
     * failing startup when no key is configured — would mean nobody can run the
     * project without first inventing one, which is the same trade
     * {@code spring.ai.google.genai.api-key} already resolves the same way.
     *
     * <p>The mitigation is that using it is <em>loud</em>: see the WARN below.
     * A deployment that never sets {@code CVEVAL_JWT_SECRET} says so in its log
     * on every boot rather than looking healthy.
     */
    private static final String DEFAULT_SECRET =
            "dev-only-insecure-signing-key-change-me-in-any-real-deployment";

    /**
     * HS256 needs at least 256 bits of key material and Nimbus enforces it.
     *
     * <p>Checked here rather than left to Nimbus because Nimbus checks at
     * <em>signing</em> time — a short key configured by hand would start the
     * application cleanly and then fail the first login with a
     * {@code KeyLengthException} that reads like an internal error.
     */
    private static final int MINIMUM_SECRET_BYTES = 32;

    private static final Duration DEFAULT_TTL = Duration.ofHours(12);

    private static final List<String> DEFAULT_ALLOWED_ORIGINS =
            List.of("http://localhost:5173", "http://localhost:3000");

    public AuthProperties {
        if (jwt == null) {
            jwt = new Jwt(null, null);
        }
        if (cors == null) {
            cors = new Cors(null);
        }
    }

    public record Jwt(String secret, Duration ttl) {

        public Jwt {
            if (secret == null || secret.isBlank()) {
                secret = DEFAULT_SECRET;
            }
            if (ttl == null || ttl.isNegative() || ttl.isZero()) {
                ttl = DEFAULT_TTL;
            }

            if (DEFAULT_SECRET.equals(secret)) {
                log.warn("cvevaluator.auth.jwt.secret is the committed development default. "
                        + "Anyone with the source can mint a valid token for any user. "
                        + "Set CVEVAL_JWT_SECRET before this is reachable by anyone else.");
            }

            int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
            if (bytes < MINIMUM_SECRET_BYTES) {
                throw new IllegalStateException(
                        "cvevaluator.auth.jwt.secret must be at least " + MINIMUM_SECRET_BYTES
                                + " bytes for HS256, but is " + bytes + ". Startup is failed here "
                                + "deliberately rather than at the first login attempt.");
            }
        }
    }

    public record Cors(List<String> allowedOrigins) {

        public Cors {
            if (allowedOrigins == null || allowedOrigins.isEmpty()) {
                allowedOrigins = DEFAULT_ALLOWED_ORIGINS;
            }
        }
    }
}
