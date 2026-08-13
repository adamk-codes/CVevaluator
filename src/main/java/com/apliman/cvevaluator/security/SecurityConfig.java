package com.apliman.cvevaluator.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * The whole authentication and authorization policy, in one file.
 *
 * <p>Deliberately URL-based rather than {@code @PreAuthorize} scattered across
 * controllers. Both work; this one can be read top to bottom and answers "who
 * can call what" without opening five other files, which is the property that
 * matters when the rules have to be defended out loud.
 *
 * <p>What it does <em>not</em> cover is ownership — "this recruiter may edit
 * <em>this</em> job". That needs the row, so it lives in the handlers, expressed
 * as scoped repository queries. See {@code JobController} and
 * {@code EvaluationController}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AuthProperties properties;

    public SecurityConfig(AuthProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            ErrorResponseWriter errors
    ) throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, exception) ->
                errors.write(response, 401, "Authentication required. Send a valid bearer token.");
        AccessDeniedHandler forbidden = (request, response, exception) ->
                errors.write(response, 403, "Your role does not permit this operation.");

        return http
                // CSRF protects cookie-borne credentials, which a browser attaches
                // to a cross-site request automatically. Nothing here is
                // cookie-borne: the token travels in an Authorization header that
                // a forged form or image tag cannot set. So the protection has
                // nothing to protect and would only break every POST.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // No session is created, ever. The token is the entire
                // conversation state, and a JSESSIONID would be a second,
                // unmanaged way to be authenticated.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Both are on by default and both would answer a 401 with
                // something other than our ErrorResponse - httpBasic with a
                // WWW-Authenticate challenge that makes browsers pop a native
                // login box, formLogin with a redirect to an HTML page that does
                // not exist in an API.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        // Getting a token cannot itself require a token.
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login")
                                .permitAll()
                        // Liveness must answer before anyone has logged in, or a
                        // health check is a login problem.
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers("/actuator/**").permitAll()

                        // Recruiters own the job lifecycle.
                        .requestMatchers(HttpMethod.POST, "/api/jobs").hasRole("RECRUITER")
                        .requestMatchers(HttpMethod.PUT, "/api/jobs/*/requirements").hasRole("RECRUITER")

                        // Candidates, and only candidates, submit CVs. A recruiter
                        // uploading a CV against their own posting would create an
                        // application whose "candidate" is the person judging it.
                        .requestMatchers(HttpMethod.POST, "/api/jobs/*/applications")
                                .hasRole("CANDIDATE")

                        // Evaluations are recruiter-only, entire. A candidate never
                        // sees the model's assessment of their own CV - not the
                        // verdict, not the scores, not the per-requirement
                        // reasoning. This is a product decision, not an oversight:
                        // the assessment is a hiring artefact written for the person
                        // making the decision.
                        //
                        // A candidate hitting these gets 403 whether or not the id
                        // exists, so the blanket refusal leaks nothing. A recruiter
                        // who does not own the job gets 404 instead - see
                        // EvaluationController.
                        .requestMatchers("/api/applications/**").hasRole("RECRUITER")

                        // Browsing postings. Candidates need this to find something
                        // to apply to, so it is authenticated rather than
                        // role-gated.
                        .requestMatchers(HttpMethod.GET, "/api/jobs", "/api/jobs/*").authenticated()

                        // The default is deny. A new endpoint is locked until
                        // someone adds a line above, which is the failure mode to
                        // prefer over a new endpoint that is public because nobody
                        // remembered it.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        // Reached when the token is present but bad - expired,
                        // malformed, or signed with the wrong key.
                        .authenticationEntryPoint(unauthenticated)
                        // Reached when the token is fine but the role is wrong.
                        .accessDeniedHandler(forbidden))
                // The same two again, and not redundantly. The handlers above are
                // the bearer-token filter's own; a request carrying no
                // Authorization header at all never enters that filter, and
                // without these it falls through to Tomcat's HTML error page
                // instead of an ErrorResponse.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .build();
    }

    /**
     * BCrypt at its default strength (10).
     *
     * <p>Chosen over Argon2 and SCrypt because it is the only one of the three
     * that needs no extra dependency — {@code spring-security-crypto} carries
     * BCrypt itself, while the others need BouncyCastle. Argon2id is the better
     * algorithm on the merits; it is not better enough to justify another jar
     * for a project of this size.
     *
     * <p>Not {@code DelegatingPasswordEncoder} (Spring's usual
     * {@code createDelegatingPasswordEncoder()}), because there are no legacy
     * hashes to migrate from — every hash in this database was written by this
     * bean. The {@code {bcrypt}} prefix it adds would be dead weight.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(
                new ImmutableSecret<com.nimbusds.jose.proc.SecurityContext>(signingKey()));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey())
                // Pinned. Without this the decoder accepts any MAC algorithm the
                // token's own header names, which lets a caller pick the weakest
                // one we would tolerate rather than the one we chose.
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        // createDefault() is exp and nbf. The issuer check is added on top so a
        // token minted by some other service sharing this key is refused here.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtIssuerValidator(JwtClaims.ISSUER)));

        return decoder;
    }

    @Bean
    public ErrorResponseWriter errorResponseWriter(ObjectMapper objectMapper) {
        return new ErrorResponseWriter(objectMapper);
    }

    /**
     * Turns the {@code role} claim into the {@code ROLE_} authority that
     * {@code hasRole(...)} looks for.
     *
     * <p>Spring's default converter reads {@code scope}/{@code scp} and prefixes
     * nothing, so without this every token authenticates successfully and then
     * fails every single {@code hasRole} check — a 403 for a user who plainly
     * has the role, which is a genuinely confusing thing to debug.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authorities);
        return converter;
    }

    /** Package-private so {@code AccessTokenRoundTripTest} can call it directly. */
    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        String role = jwt.getClaimAsString(JwtClaims.ROLE);
        return role == null
                ? List.of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // Authorization has to be named explicitly - it is not one of the CORS
        // "simple" headers, so a browser will not send it on a cross-origin
        // request unless the preflight allows it by name.
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // No allowCredentials(true): the token is in a header, not a cookie, so
        // there are no credentials for the browser to attach. Turning it on
        // would also make the allowed-origins list mandatory rather than merely
        // advisable, which it already is.
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /**
     * The HMAC key, derived from the configured secret.
     *
     * <p>Raw UTF-8 bytes of the property, not a hash or a KDF of it. That means
     * the key's real entropy is whatever the operator's string had — which is
     * why {@link AuthProperties} enforces a 32-byte floor and warns loudly about
     * the committed default.
     */
    private SecretKey signingKey() {
        return new SecretKeySpec(
                properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
