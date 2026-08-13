package com.apliman.cvevaluator.auth.dto;

import com.apliman.cvevaluator.user.Role;
import com.apliman.cvevaluator.user.User;

/**
 * The authenticated user as the frontend needs them: enough to render a name
 * and to decide which screens exist.
 *
 * <p>A record built by hand from the entity rather than the entity itself —
 * {@code User} carries {@code passwordHash}, and an entity on the wire is the
 * one rule in CLAUDE.md that this would break most expensively.
 */
public record UserResponse(Long id, String name, String email, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
