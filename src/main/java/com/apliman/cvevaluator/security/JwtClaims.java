package com.apliman.cvevaluator.security;

/**
 * The shape of the tokens this application issues to itself.
 *
 * <p>One place rather than string literals at both ends, because the issuer and
 * the verifier are separate classes and a typo in either is not a compile
 * error — it is a token that verifies fine and then carries no role, which
 * looks like an authorization bug rather than a spelling one.
 */
public final class JwtClaims {

    /**
     * The {@code iss} claim, checked on every incoming token.
     *
     * <p>Not strictly load-bearing while this application is the only issuer
     * signing with this key. It is here so that the day a second service shares
     * the secret, tokens minted for that service are rejected here rather than
     * quietly accepted.
     */
    public static final String ISSUER = "cvevaluator";

    /**
     * The user's {@link com.apliman.cvevaluator.user.Role}, as its enum name.
     *
     * <p>Deliberately not {@code scope}/{@code scp}, which is what Spring's
     * default {@code JwtGrantedAuthoritiesConverter} reads. This is not an OAuth2
     * deployment and there are no scopes — there is exactly one role per user.
     * The custom converter in {@link SecurityConfig} is the price of saying so.
     */
    public static final String ROLE = "role";

    /**
     * Carried for readability when someone pastes a token into jwt.io while
     * debugging. Nothing authorizes on it — {@code sub} is the identity.
     */
    public static final String EMAIL = "email";

    private JwtClaims() {
    }
}
