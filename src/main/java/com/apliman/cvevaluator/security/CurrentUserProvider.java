package com.apliman.cvevaluator.security;

/**
 * Who is making this request.
 *
 * <p>The seam. It was built when authentication was stubbed so that swapping in
 * a real implementation would be a one-bean change, and that is what happened:
 * {@link JwtCurrentUserProvider} replaced the header-reading stub without any
 * caller changing. Kept as an interface because it is still the boundary that
 * lets a handler ask "who is this" without knowing that the answer comes from a
 * signed token.
 */
public interface CurrentUserProvider {

    /**
     * @return the authenticated user's id, never null
     * @throws IllegalStateException if there is no authenticated user on this
     *         thread — which, behind the filter chain, means the caller is not
     *         on a request thread at all
     */
    Long currentUserId();
}