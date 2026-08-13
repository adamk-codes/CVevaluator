package com.apliman.cvevaluator.auth.dto;

import java.time.Instant;

/**
 * What register and login both return.
 *
 * <p>Register returns a token as well as creating the account, so the frontend
 * does not have to immediately post the same credentials again to be logged in.
 *
 * @param tokenType always {@code "Bearer"}. Constant, and included anyway so the
 *                  client builds the header from the response rather than from a
 *                  string a frontend developer had to be told.
 * @param expiresAt when the token stops being accepted. Sent so the client can
 *                  send the user back to the login screen before a request
 *                  fails, rather than discovering expiry as a surprise 401 in
 *                  the middle of something.
 */
public record AuthResponse(
        String token,
        String tokenType,
        Instant expiresAt,
        UserResponse user
) {

    public AuthResponse(String token, Instant expiresAt, UserResponse user) {
        this(token, "Bearer", expiresAt, user);
    }
}
