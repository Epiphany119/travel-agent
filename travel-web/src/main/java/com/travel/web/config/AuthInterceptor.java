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
        boolean privatePath = req.getRequestURI().startsWith(req.getContextPath() + "/api/notes")
                || req.getRequestURI().matches(".*/api/user/(preferences|nickname|avatar|reputation|social/.*|journeys|inspirations|travel-notes).*");
        boolean publicPath = req.getRequestURI().contains("/share/") || req.getRequestURI().matches(".*/api/user/(social/notes|users/[^/]+/profile)$");
        if (privatePath && !publicPath && userId == null) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (userId != null) {
            for (String name : new String[]{"userId", "reporterId", "reviewerId", "from"}) {
                String supplied = req.getParameter(name);
                if (supplied != null && !supplied.isBlank() && !supplied.equals(userId) && !"platform".equals(supplied)) {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return false;
                }
            }
        }
        return true; // public endpoints remain public; private endpoints enforce the attribute.
    }
}
