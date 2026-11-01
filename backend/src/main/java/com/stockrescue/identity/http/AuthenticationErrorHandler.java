package com.stockrescue.identity.http;

import com.stockrescue.identity.application.AuthenticationBusyException;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.platform.http.ApiProblems;
import com.stockrescue.platform.http.ProblemCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthenticationErrorHandler {
    private final ApiProblems problems;
    public AuthenticationErrorHandler(ApiProblems problems) { this.problems = problems; }

    @ExceptionHandler(AuthenticationBusyException.class)
    public ResponseEntity<ApiProblem> busy() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_PROBLEM_JSON).header("Retry-After", "2")
                .body(problems.create(HttpStatus.TOO_MANY_REQUESTS, ProblemCode.AUTH_RATE_LIMITED, "Wait a moment before trying again."));
    }
}
