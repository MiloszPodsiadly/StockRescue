package com.stockrescue.platform.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

import com.stockrescue.platform.http.ApiProblems;
import com.stockrescue.platform.http.ProblemCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

final class AuthRequestFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 4096;
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final ApiProblems problems;

    AuthRequestFilter(ApiProblems problems) { this.problems = problems; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest accepted = request;
        if (request.getMethod().equals("POST") && (request.getRequestURI().equals("/api/v1/auth/sign-in")
                || request.getRequestURI().equals("/api/v1/auth/sign-up") || request.getRequestURI().equals("/api/v1/invitations/accept"))) {
            var body = request.getContentLengthLong() > MAX_BYTES ? null : request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (body == null || body.length > MAX_BYTES) {
                problems.write(response, HttpStatus.CONTENT_TOO_LARGE, ProblemCode.REQUEST_TOO_LARGE, "The request is too large.");
                return;
            }
            accepted = new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input = new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous request body"); }
                    };
                }
            };
        }
        // CSRF bootstrap is the only anonymous API operation that may create a session.
        if (accepted.getSession(false) == null) {
            problems.write(response, HttpStatus.FORBIDDEN, ProblemCode.CSRF_INVALID, "Refresh this page and try again.");
            return;
        }
        chain.doFilter(accepted, response);
    }
}
