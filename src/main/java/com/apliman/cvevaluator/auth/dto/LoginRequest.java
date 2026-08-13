package com.apliman.cvevaluator.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * No {@code @Email} and no {@code @Size} here, unlike
 * {@link RegisterRequest}.
 *
 * <p>That asymmetry is deliberate. Validating the shape of a login credential
 * hands an attacker a free oracle: "that is not a valid email" and "that email
 * has no account" are different responses, and the first one is cheaper to
 * provoke. Everything that is not a correct pair gets the same 401 with the
 * same message.
 */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {}
