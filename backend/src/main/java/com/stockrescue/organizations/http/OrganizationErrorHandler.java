package com.stockrescue.organizations.http;

import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.organizations.application.OnboardingConflictException;
import com.stockrescue.organizations.application.InvalidOnboardingException;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.platform.http.ApiProblems;
import com.stockrescue.platform.http.ProblemCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = OrganizationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrganizationErrorHandler {
    private final ApiProblems problems;

    public OrganizationErrorHandler(ApiProblems problems) { this.problems = problems; }

    @ExceptionHandler(OrganizationUnavailableException.class)
    public ResponseEntity<ApiProblem> unavailable() {
        return problem(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND, "The requested resource is unavailable.");
    }

    @ExceptionHandler(OnboardingConflictException.class)
    public ResponseEntity<ApiProblem> conflict() {
        return problem(HttpStatus.CONFLICT, ProblemCode.ONBOARDING_CONFLICT, "Onboarding is already completed or the retry changed. Refresh your organization list.");
    }

    @ExceptionHandler(InvalidOnboardingException.class)
    public ResponseEntity<ApiProblem> invalid() {
        return problem(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_REQUEST, "Review the organization fields.");
    }

    @ExceptionHandler(OrganizationSelectionRequiredException.class)
    public ResponseEntity<ApiProblem> selectionRequired() {
        return problem(HttpStatus.FORBIDDEN, ProblemCode.ORGANIZATION_REQUIRED, "Select an organization to continue.");
    }

    private ResponseEntity<ApiProblem> problem(HttpStatus status, ProblemCode code, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problems.create(status, code, detail));
    }
}
