package com.stockrescue.platform.http;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private final ObjectMapper objectMapper;

    public CorrelationIdFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        var values = Collections.list(request.getHeaders(HEADER_NAME));
        boolean invalid = values.size() > 1
                || (values.size() == 1 && !SAFE_ID.matcher(values.getFirst()).matches());
        String correlationId = values.isEmpty() || invalid
                ? UUID.randomUUID().toString() : values.getFirst();
        response.setHeader(HEADER_NAME, correlationId);

        try (var ignored = MDC.putCloseable("correlationId", correlationId)) {
            if (invalid) {
                LOG.warn("Rejected request with invalid or duplicate X-Correlation-ID");
                response.setStatus(HttpStatus.BAD_REQUEST.value());
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                        "X-Correlation-ID must contain 1 to 64 letters, digits, dots, underscores or hyphens, starting with a letter or digit.");
                objectMapper.writeValue(response.getOutputStream(), problem);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
