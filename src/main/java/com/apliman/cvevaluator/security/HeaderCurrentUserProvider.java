package com.apliman.cvevaluator.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class HeaderCurrentUserProvider implements CurrentUserProvider {

    private final HttpServletRequest request;

    public HeaderCurrentUserProvider(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public Long currentUserId() {
        String header = request.getHeader("X-User-Id");
        if (header == null || header.isBlank()) {
            throw new IllegalStateException("Missing X-User-Id header");
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("X-User-Id must be a valid number: " + header);
        }
    }
}