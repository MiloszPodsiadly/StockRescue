package com.stockrescue.access.http;

import com.stockrescue.access.application.AccessFailure;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.platform.http.ApiProblems;
import com.stockrescue.platform.http.ProblemCode;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = StaffController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessErrorHandler {
    private final ApiProblems problems;
    public AccessErrorHandler(ApiProblems problems) { this.problems = problems; }
    @ExceptionHandler(OrganizationUnavailableException.class)
    public ResponseEntity<ApiProblem> unavailable() {
        return response(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND, "The requested resource is unavailable.");
    }
    @ExceptionHandler(AccessFailure.class)
    public ResponseEntity<ApiProblem> failure(AccessFailure failure) {
        return switch (failure.kind()) {
            case INVALID_INPUT -> response(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_REQUEST, "Review the access change fields.");
            case CONFLICT -> response(HttpStatus.CONFLICT, ProblemCode.ACCESS_CONFLICT, "Access changed, this transition is unavailable, or the last active administrator must remain. Reload and review.");
            case INVITATION_UNAVAILABLE -> response(HttpStatus.NOT_FOUND, ProblemCode.INVITATION_UNAVAILABLE, "This invitation is unavailable for this account.");
            case RATE_LIMITED -> response(HttpStatus.TOO_MANY_REQUESTS, ProblemCode.ACCESS_RATE_LIMITED, "Too many invitation attempts. Try again later.");
        };
    }
    private ResponseEntity<ApiProblem> response(HttpStatus status, ProblemCode code, String detail) {
        LoggerFactory.getLogger(AccessErrorHandler.class).warn("Access request denied; outcome=DENIED code={}", code);
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.TOO_MANY_REQUESTS) response.header("Retry-After", "3600");
        return response.body(problems.create(status, code, detail));
    }
}
