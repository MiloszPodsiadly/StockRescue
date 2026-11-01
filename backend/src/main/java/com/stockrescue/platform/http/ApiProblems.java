package com.stockrescue.platform.http;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiProblems {
    private final ObjectMapper mapper;

    public ApiProblems(ObjectMapper mapper) { this.mapper = mapper; }

    public ApiProblem create(HttpStatus status, ProblemCode code, String detail) {
        return new ApiProblem(URI.create("about:blank"), status.getReasonPhrase(), status.value(),
                detail, code, MDC.get("correlationId"));
    }

    public void write(HttpServletResponse response, HttpStatus status, ProblemCode code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), create(status, code, detail));
    }
}
