package com.apliman.cvevaluator.security;

import com.apliman.cvevaluator.user.Role;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Authenticated callers, for MockMvc.
 *
 * <p>These install a verified {@code Jwt} into the security context directly
 * rather than signing a real token and sending an {@code Authorization} header.
 * That is the right trade for a slice test — it exercises the authorization
 * rules without also re-testing Nimbus's signature verification on every case —
 * but it does mean these tests say nothing about whether a token this
 * application <em>mints</em> is one it will <em>accept</em>. That round trip is
 * covered once, for real, in {@code AccessTokenRoundTripTest}.
 */
public final class TestTokens {

    public static RequestPostProcessor recruiter(long userId) {
        return as(userId, Role.RECRUITER);
    }

    public static RequestPostProcessor candidate(long userId) {
        return as(userId, Role.CANDIDATE);
    }

    /**
     * Authorities are set explicitly rather than left to the post-processor's
     * default, which derives them from a {@code scope} claim these tokens do not
     * have. Setting both the claim and the authority keeps this consistent with
     * what {@code SecurityConfig}'s converter produces from a real token.
     */
    private static RequestPostProcessor as(long userId, Role role) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt
                        .subject(String.valueOf(userId))
                        .claim(JwtClaims.ROLE, role.name()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    private TestTokens() {
    }
}
