package com.apliman.cvevaluator.security;

import com.apliman.cvevaluator.user.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The identity of whoever sent the request being handled, read from the
 * verified bearer token.
 *
 * <p>This is the bean the {@code CurrentUserProvider} seam was built for. It
 * replaced {@code HeaderCurrentUserProvider}, which trusted an {@code X-User-Id}
 * header — that class is gone rather than merely unused, because a header-trusting
 * provider left on the classpath is one {@code @Primary} away from being an
 * authentication bypass.
 *
 * <p><strong>Only valid on a request thread.</strong> The security context is a
 * {@code ThreadLocal} and is not propagated to the {@code @Async} pools that run
 * extraction and evaluation. Nothing on those threads calls this today; anything
 * that starts to must be handed the id from the request thread rather than
 * asking here, where it would find nothing.
 */
@Component
public class JwtCurrentUserProvider implements CurrentUserProvider {

    @Override
    public Long currentUserId() {
        Jwt jwt = requireJwt();
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            // Only reachable via a token this application did not mint, which
            // means someone holds the signing key. Worth a distinct message.
            throw new IllegalStateException("Token subject is not a user id: " + jwt.getSubject());
        }
    }

    /**
     * The caller's role, straight off the token.
     *
     * <p>Used for the ownership rules that need to distinguish a recruiter from
     * a candidate <em>within</em> a handler, rather than the coarse role gate the
     * filter chain already applied to the URL.
     */
    public Role currentUserRole() {
        String role = requireJwt().getClaimAsString(JwtClaims.ROLE);
        if (role == null) {
            throw new IllegalStateException("Token carries no role claim");
        }
        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Token carries an unknown role: " + role);
        }
    }

    private Jwt requireJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Unreachable through the filter chain, which rejects unauthenticated
        // requests before any handler runs. It fires when this is called off a
        // request thread - see the class note - and saying so beats a
        // NullPointerException three frames down.
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException(
                    "No authenticated user on this thread. Either the request bypassed the "
                            + "security filter chain, or this was called from an @Async pool.");
        }
        return jwt;
    }
}
