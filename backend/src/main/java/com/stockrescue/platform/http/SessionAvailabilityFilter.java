package com.stockrescue.platform.http;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER - 1)
public class SessionAvailabilityFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(SessionAvailabilityFilter.class);
    private final ApiProblems problems;

    public SessionAvailabilityFilter(ApiProblems problems) { this.problems = problems; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (DataAccessException | TransactionException failure) {
            LOG.error("Session storage unavailable; exceptionType={}", failure.getClass().getName());
            if (!response.isCommitted()) {
                response.resetBuffer();
                problems.write(response, HttpStatus.SERVICE_UNAVAILABLE, ProblemCode.SERVICE_UNAVAILABLE, "The service is temporarily unavailable. Try again shortly.");
            }
        }
    }
}
