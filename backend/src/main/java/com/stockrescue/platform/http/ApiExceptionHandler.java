package com.stockrescue.platform.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiProblems problems;

    public ApiExceptionHandler(ApiProblems problems) { this.problems = problems; }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiProblem> handleAuthenticationFailure(AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problems.create(HttpStatus.UNAUTHORIZED,
                ProblemCode.INVALID_CREDENTIALS, "Sign-in could not be completed with these credentials."));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiProblem> handleAccessDenied(org.springframework.security.access.AccessDeniedException exception) {
        LOG.warn("Access request denied; outcome=DENIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problems.create(HttpStatus.FORBIDDEN, ProblemCode.ACCESS_DENIED, "This action is unavailable."));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var httpStatus = HttpStatus.valueOf(status.value());
        var code = httpStatus == HttpStatus.NOT_FOUND ? ProblemCode.RESOURCE_NOT_FOUND : ProblemCode.INVALID_REQUEST;
        var detail = httpStatus == HttpStatus.NOT_FOUND ? "The requested resource is unavailable." : "Review the submitted fields.";
        var responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problems.create(httpStatus, code, detail), responseHeaders, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handleUnexpectedFailure(Exception exception) {
        LOG.error("Request failed; exceptionType={}", exception.getClass().getName());
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problems.create(HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemCode.INTERNAL_ERROR, "The request could not be completed."));
    }
}
