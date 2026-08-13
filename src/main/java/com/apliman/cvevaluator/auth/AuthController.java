package com.apliman.cvevaluator.auth;

import com.apliman.cvevaluator.auth.dto.AuthResponse;
import com.apliman.cvevaluator.auth.dto.LoginRequest;
import com.apliman.cvevaluator.auth.dto.RegisterRequest;
import com.apliman.cvevaluator.auth.dto.UserResponse;
import com.apliman.cvevaluator.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Getting a token, and finding out who one belongs to.
 *
 * <p>The two POSTs are the only endpoints in the application reachable without
 * a token — see the {@code permitAll} lines in
 * {@code SecurityConfig}. Everything else, including the GET below, is behind
 * the filter chain.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final CurrentUserProvider currentUser;

    public AuthController(AuthService auth, CurrentUserProvider currentUser) {
        this.auth = auth;
        this.currentUser = currentUser;
    }

    /**
     * 201, with the new account and a token for it.
     *
     * <p>No {@code Location} header: there is no {@code GET /api/users/{id}} to
     * point one at, and {@code /api/auth/me} is not the created resource — it is
     * whoever is asking.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(request));
    }

    /**
     * 200 and a token, or 401 and the same message for every kind of failure.
     */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request);
    }

    /**
     * Who this token belongs to.
     *
     * <p>Exists for the frontend's page-load path: a token recovered from
     * storage is a string with no name or role attached, and decoding the claims
     * client-side would mean trusting a value the client itself can rewrite.
     * Asking here is one request and the answer is authoritative.
     */
    @GetMapping("/me")
    public UserResponse me() {
        return auth.describe(currentUser.currentUserId());
    }
}
