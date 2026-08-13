package com.apliman.cvevaluator.auth;

import com.apliman.cvevaluator.security.AccessTokenIssuer;
import com.apliman.cvevaluator.security.TestTokens;
import com.apliman.cvevaluator.security.WithSecurityRules;
import com.apliman.cvevaluator.user.Role;
import com.apliman.cvevaluator.user.User;
import com.apliman.cvevaluator.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registration, login and {@code /me} as a client experiences them.
 *
 * <p>{@link AuthService} is imported rather than mocked, and the real
 * {@code PasswordEncoder} comes with {@code @WithSecurityRules} — so these
 * exercise actual BCrypt hashing and matching rather than a stub agreeing that
 * a password was correct. Only the repository and the token issuer are mocked.
 */
@WebMvcTest(AuthController.class)
@Import(AuthService.class)
@WithSecurityRules
class AuthControllerTest {

    private static final long USER_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository users;

    @MockitoBean
    private AccessTokenIssuer tokens;

    @Test
    void register_createsTheAccountAndReturnsATokenFor201() throws Exception {
        when(users.existsByEmail("new@example.com")).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            ReflectionTestUtils.setField(user, "id", USER_ID);
            return user;
        });
        issuesToken();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "New Person", "email": "new@example.com",
                                 "password": "correct-horse", "role": "CANDIDATE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("signed-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.user.id").value(USER_ID))
                .andExpect(jsonPath("$.user.email").value("new@example.com"))
                .andExpect(jsonPath("$.user.role").value("CANDIDATE"))
                // The entity is never on the wire, so the hash cannot be either.
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    /**
     * The password must never be stored as sent. This is the single most
     * important assertion in the file.
     */
    @Test
    void register_storesABcryptHashAndNotThePassword() throws Exception {
        when(users.existsByEmail("new@example.com")).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        issuesToken();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "New Person", "email": "new@example.com",
                                 "password": "correct-horse", "role": "CANDIDATE"}
                                """))
                .andExpect(status().isCreated());

        verify(users).saveAndFlush(org.mockito.ArgumentMatchers.argThat(user ->
                !"correct-horse".equals(user.getPasswordHash())
                        && user.getPasswordHash().startsWith("$2")));
    }

    /**
     * Addresses are case-folded on the way in, so one account cannot become
     * two. Without this, {@code Adam@x.com} and {@code adam@x.com} are separate
     * rows — Postgres compares {@code varchar} case-sensitively, so the unique
     * index does not stop it.
     */
    @Test
    void register_lowercasesTheEmail() throws Exception {
        when(users.existsByEmail("mixed@example.com")).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        issuesToken();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Mixed", "email": "MiXeD@Example.COM",
                                 "password": "correct-horse", "role": "RECRUITER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("mixed@example.com"));

        verify(users).existsByEmail("mixed@example.com");
    }

    /**
     * Surrounding whitespace is a 400, not something quietly trimmed.
     *
     * <p>Pinned because it is not what you would guess from reading
     * {@code AuthService.normalise}, which does call {@code trim()}. Bean
     * Validation runs first and {@code @Email} rejects a padded address before
     * the service is entered, so on this path the trim never fires. It still
     * matters on login, where {@code LoginRequest} deliberately carries no
     * {@code @Email}.
     */
    @Test
    void register_emailWithSurroundingWhitespace_is400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Padded", "email": "  padded@example.com  ",
                                 "password": "correct-horse", "role": "RECRUITER"}
                                """))
                .andExpect(status().isBadRequest());

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void register_duplicateEmail_is409() throws Exception {
        when(users.existsByEmail("taken@example.com")).thenReturn(true);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Someone", "email": "taken@example.com",
                                 "password": "correct-horse", "role": "CANDIDATE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        verify(users, never()).saveAndFlush(any());
    }

    /**
     * The race the pre-check cannot win: two registrations of the same address
     * both pass {@code existsByEmail} and only the unique index settles it. That
     * has to be a 409 too, not a 500.
     */
    @Test
    void register_losingTheUniqueIndexRace_isStill409() throws Exception {
        when(users.existsByEmail("taken@example.com")).thenReturn(false);
        when(users.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Someone", "email": "taken@example.com",
                                 "password": "correct-horse", "role": "CANDIDATE"}
                                """))
                .andExpect(status().isConflict())
                // Never the raw Hibernate message, which names the constraint
                // and the table.
                .andExpect(jsonPath("$.message").value("An account already exists for taken@example.com"));
    }

    @Test
    void register_shortPassword_is400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Someone", "email": "new@example.com",
                                 "password": "short", "role": "CANDIDATE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("password")));

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void login_correctPassword_returnsAToken() throws Exception {
        when(users.findByEmail("known@example.com")).thenReturn(Optional.of(storedUser()));
        issuesToken();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "known@example.com", "password": "correct-horse"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("signed-token"))
                .andExpect(jsonPath("$.user.id").value(USER_ID));
    }

    /**
     * The enumeration defence. A wrong password and an unknown address must be
     * the same 401 with the same message — if they differ, an anonymous caller
     * can discover which addresses have accounts.
     */
    @Test
    void login_wrongPasswordAndUnknownEmail_areIndistinguishable() throws Exception {
        when(users.findByEmail("known@example.com")).thenReturn(Optional.of(storedUser()));
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        String wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "known@example.com", "password": "wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownEmail = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nobody@example.com", "password": "wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Compared on the message rather than the whole body, which carries a
        // timestamp that differs between the two calls.
        org.assertj.core.api.Assertions
                .assertThat(messageOf(wrongPassword))
                .isEqualTo(messageOf(unknownEmail))
                .isEqualTo("Invalid email or password");
    }

    @Test
    void me_returnsTheUserBehindTheToken() throws Exception {
        when(users.findById(USER_ID)).thenReturn(Optional.of(storedUser()));

        mockMvc.perform(get("/api/auth/me").with(TestTokens.candidate(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID))
                .andExpect(jsonPath("$.email").value("known@example.com"))
                .andExpect(jsonPath("$.role").value("CANDIDATE"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void me_withoutAToken_is401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    /** A live token for an account that has since been deleted. */
    @Test
    void me_forADeletedUser_is401() throws Exception {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/auth/me").with(TestTokens.candidate(USER_ID)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The two POSTs are the only unauthenticated endpoints in the application.
     * If this starts failing, the permitAll rules moved.
     */
    @Test
    void registerAndLogin_areReachableWithoutTokens() throws Exception {
        when(users.existsByEmail(any())).thenReturn(true);
        when(users.findByEmail(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "X", "email": "x@example.com",
                                 "password": "correct-horse", "role": "CANDIDATE"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "x@example.com", "password": "correct-horse"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    private void issuesToken() {
        when(tokens.issue(any(User.class))).thenReturn(
                new AccessTokenIssuer.IssuedToken("signed-token", java.time.Instant.now().plusSeconds(3600)));
    }

    /**
     * A user whose stored hash is a real BCrypt hash of "correct-horse",
     * computed at construction by the same encoder the application uses — so
     * these tests break if the encoder is swapped for an incompatible one.
     */
    private static User storedUser() {
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .encode("correct-horse");
        User user = new User("Known", hash, "known@example.com", Role.CANDIDATE);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private static String messageOf(String body) {
        return com.jayway.jsonpath.JsonPath.read(body, "$.message");
    }
}
