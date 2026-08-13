package com.apliman.cvevaluator.auth.dto;

import com.apliman.cvevaluator.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param password minimum 8, maximum 72. The floor is a policy choice; the
 *                 ceiling is BCrypt's, and it is not advisory — BCrypt hashes
 *                 the first 72 bytes and silently ignores the rest, so without
 *                 this a 100-character password and its first 72 characters are
 *                 the same credential. Rejecting is honest where truncating is
 *                 not.
 * @param role     chosen by the registrant, which is only acceptable because
 *                 there is no privileged role to escalate into — RECRUITER and
 *                 CANDIDATE see different things, neither outranks the other.
 *                 The day an ADMIN role exists, this field has to stop being
 *                 client-supplied.
 */
public record RegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull Role role
) {}
