package com.stockrescue.platform.security;

import java.io.IOException;
import java.util.UUID;

import com.stockrescue.platform.http.ApiProblems;
import com.stockrescue.platform.http.ProblemCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class AccountSessionFilter extends OncePerRequestFilter {
    private final SessionAccountStatus accounts;
    private final WebSessions sessions;
    private final ApiProblems problems;
    private final long maximumAgeMillis;

    AccountSessionFilter(SessionAccountStatus accounts, WebSessions sessions, ApiProblems problems, long maximumAgeSeconds) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.problems = problems;
        this.maximumAgeMillis = Math.multiplyExact(maximumAgeSeconds, 1000);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            var session = request.getSession(false);
            Object since = session == null ? null : session.getAttribute(WebSessions.AUTHENTICATED_AT);
            boolean active = false;
            try {
                active = since instanceof Long time && System.currentTimeMillis() - time < maximumAgeMillis
                        && accounts.active(UUID.fromString(authentication.getName()));
            } catch (IllegalArgumentException ignored) {
                // An invalid persisted principal is never accepted as an identity.
            }
            if (!active) {
                sessions.signOut(request, response);
                problems.write(response, HttpStatus.UNAUTHORIZED, ProblemCode.SESSION_EXPIRED, "Sign in again to continue.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
