package com.travel.web.config;

import com.travel.auth.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** Validates bearer sessions once and exposes the trusted user id as a request attribute. */
public class AuthInterceptor implements HandlerInterceptor {
    public static final String USER_ID = "authenticatedUserId";
    private final TokenService tokens;
    public AuthInterceptor(TokenService tokens) { this.tokens = tokens; }
    @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String header = req.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
        String userId = tokens.verify(token);
        if (userId != null) req.setAttribute(USER_ID, userId);
        return true; // public endpoints remain public; private endpoints enforce the attribute.
    }
}
