package com.apliman.cvevaluator.security;

import com.apliman.cvevaluator.common.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes an {@link ErrorResponse} straight to the servlet response.
 *
 * <p><strong>This exists because of a trap worth knowing.</strong>
 * {@code GlobalExceptionHandler} is a {@code @RestControllerAdvice}, and advice
 * only sees exceptions thrown from inside {@code DispatcherServlet}. Spring
 * Security rejects a missing or invalid token in the <em>filter chain</em>,
 * which runs before the dispatcher is ever entered — so the advice never fires,
 * and the client gets Tomcat's default HTML error page instead of the
 * {@code ErrorResponse} every other failure in this application produces.
 *
 * <p>CLAUDE.md says every error goes out as an {@code ErrorResponse} and never
 * as a Spring default error page. Honouring that for authentication failures
 * means writing the body by hand, here, from the two Spring Security callbacks
 * that sit at that layer.
 *
 * <p>Registered as a {@code @Bean} in {@link SecurityConfig} rather than being
 * a {@code @Component}, so that one {@code @Import(SecurityConfig.class)} pulls
 * the whole security story into a {@code @WebMvcTest} slice.
 */
public class ErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public ErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // The injected mapper, not a fresh one: it is the same instance the
        // message converters use, so the Instant on ErrorResponse serialises
        // identically here and in every controller-produced error. A bare
        // `new ObjectMapper()` would render it as an epoch-seconds object.
        response.getWriter().write(objectMapper.writeValueAsString(
                new ErrorResponse(status, message)));
    }
}
