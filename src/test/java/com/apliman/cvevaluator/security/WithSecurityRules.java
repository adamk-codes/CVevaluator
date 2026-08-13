package com.apliman.cvevaluator.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs a {@code @WebMvcTest} slice against the application's real security
 * rules.
 *
 * <p>Needed because a slice does <em>not</em> pick these up on its own. That is
 * the trap this annotation exists to close: {@code @WebMvcTest} auto-configures
 * Spring Security, but it configures Boot's <em>default</em> chain — every
 * request authenticated, HTTP Basic, a generated password — not
 * {@link SecurityConfig}. A slice without this compiles, runs, and cheerfully
 * asserts against rules the application does not have.
 *
 * <p>{@link JwtCurrentUserProvider} is imported too, deliberately, rather than
 * being mocked in each test. It means {@code currentUserId()} in a test returns
 * whatever the token in {@link TestTokens} said, through the same
 * {@code SecurityContext} the real filter chain populates — so a test that
 * passes the wrong user's token fails for the right reason.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import({ SecurityConfig.class, JwtCurrentUserProvider.class })
@EnableConfigurationProperties(AuthProperties.class)
public @interface WithSecurityRules {
}
