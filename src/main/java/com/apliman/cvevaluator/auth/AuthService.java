package com.apliman.cvevaluator.auth;

import com.apliman.cvevaluator.auth.dto.AuthResponse;
import com.apliman.cvevaluator.auth.dto.LoginRequest;
import com.apliman.cvevaluator.auth.dto.RegisterRequest;
import com.apliman.cvevaluator.auth.dto.UserResponse;
import com.apliman.cvevaluator.security.AccessTokenIssuer;
import com.apliman.cvevaluator.user.User;
import com.apliman.cvevaluator.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * Registration and login.
 */
@Service
public class AuthService {

    /**
     * A valid BCrypt hash of a password nobody has.
     *
     * <p>Compared against when the email does not exist, purely to spend the
     * same ~100ms a real comparison spends. Without it, "no such account"
     * returns in about a millisecond and "wrong password" takes a hundred, and
     * that difference is measurable over the network — which would hand back
     * exactly the account enumeration that {@link InvalidCredentialsException}'s
     * single shared message exists to prevent. The identical message with a
     * tell-tale response time is a defence in name only.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer tokens;

    public AuthService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer tokens
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    /**
     * Creates an account and returns a token for it, so the client is logged in
     * without a second round trip.
     *
     * <p>The duplicate check is done twice — once by reading, once by catching
     * the constraint violation. The read gives the good error message; the catch
     * is what actually makes it correct, because two simultaneous registrations
     * of the same address both pass the read and only the unique index can
     * settle it. Relying on the read alone turns that race into a 500.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalise(request.email());

        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }

        User user = new User(
                request.name().trim(),
                passwordEncoder.encode(request.password()),
                email,
                request.role());

        User saved;
        try {
            // saveAndFlush, not save. A plain save inside this transaction would
            // defer the INSERT to commit time, which happens after this method
            // returns - so the constraint violation would surface somewhere the
            // catch below cannot see it, as a 500.
            saved = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyRegisteredException(email);
        }

        return respondWith(saved);
    }

    /**
     * Exchanges a correct email and password for a token.
     *
     * <p>Read-only: a login writes nothing. There is no last-login column and no
     * failed-attempt counter, so there is no lockout either — a note for the
     * demo rather than a gap to fill silently. Rate limiting is explicitly out
     * of scope for this project.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Optional<User> found = users.findByEmail(normalise(request.email()));

        // Unconditional, and the branch order matters: the comparison happens
        // whether or not the account exists. See DUMMY_HASH.
        String hash = found.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean matches = passwordEncoder.matches(request.password(), hash);

        if (found.isEmpty() || !matches) {
            throw new InvalidCredentialsException();
        }

        return respondWith(found.get());
    }

    /**
     * Re-reads the user behind a token.
     *
     * <p>Backs {@code GET /api/auth/me}, which the frontend calls on page load
     * to turn a stored token back into a session. Reads the database rather than
     * trusting the token's own {@code email} and {@code role} claims, so a
     * renamed user sees their new name — and so a deleted user's still-valid
     * token fails here instead of appearing to work.
     */
    @Transactional(readOnly = true)
    public UserResponse describe(Long userId) {
        return users.findById(userId)
                .map(UserResponse::from)
                // The token verified, so this is a user who was deleted while
                // holding one. Not a 404: the missing thing is the caller.
                .orElseThrow(InvalidCredentialsException::new);
    }

    private AuthResponse respondWith(User user) {
        AccessTokenIssuer.IssuedToken token = tokens.issue(user);
        return new AuthResponse(token.value(), token.expiresAt(), UserResponse.from(user));
    }

    /**
     * Lower-cased and trimmed, on both the registration and the login path.
     *
     * <p>The fold is what makes the unique index mean "one account per address"
     * rather than "one account per spelling" — Postgres compares {@code varchar}
     * case-sensitively, so without it {@code Adam@x.com} and {@code adam@x.com}
     * are two accounts.
     *
     * <p>{@code Locale.ROOT} rather than the default locale: in a Turkish
     * locale, {@code "I".toLowerCase()} is {@code "ı"}, not {@code "i"}, so a
     * default-locale fold would store and look up different strings depending on
     * where the server runs.
     *
     * <p>The {@code trim()} only ever fires on the login path. {@code @Email} on
     * {@code RegisterRequest} rejects a padded address before this method is
     * reached, so registration answers 400 rather than quietly accepting it —
     * see {@code AuthControllerTest}. Kept here so that a login typed with a
     * trailing space still matches the account it belongs to.
     */
    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
