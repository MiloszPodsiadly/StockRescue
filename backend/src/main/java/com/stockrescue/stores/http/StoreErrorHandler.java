package com.stockrescue.stores.http;

import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.platform.http.ApiProblems;
import com.stockrescue.platform.http.ProblemCode;
import com.stockrescue.stores.domain.StoreFailure;
import com.stockrescue.stores.domain.ConfigurationFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = StoreController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StoreErrorHandler {
    private final ApiProblems problems;
    public StoreErrorHandler(ApiProblems problems) { this.problems = problems; }
    @ExceptionHandler(OrganizationUnavailableException.class)
    public ResponseEntity<ApiProblem> unavailable() { return response(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND, "The requested resource is unavailable."); }
    @ExceptionHandler(StoreFailure.class)
    public ResponseEntity<ApiProblem> failure(StoreFailure failure) {
        return switch (failure.kind()) {
            case INVALID_INPUT -> response(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_REQUEST, "Review store fields and use a valid IANA timezone identifier.");
            case CONFLICT -> response(HttpStatus.CONFLICT, ProblemCode.STORE_CONFLICT, "This code is reserved, the retry changed, or a newer version was saved. Reload and review.");
            case INACTIVE -> response(HttpStatus.CONFLICT, ProblemCode.STORE_INACTIVE, "This store is inactive. New operations are unavailable.");
        };
    }
    @ExceptionHandler(ConfigurationFailure.class)
    public ResponseEntity<ApiProblem> configuration(ConfigurationFailure failure) {
        return switch(failure.kind()) {
            case INVALID -> response(HttpStatus.BAD_REQUEST,ProblemCode.INVALID_REQUEST,"Review configuration fields, precision and units.");
            case CONFLICT -> response(HttpStatus.CONFLICT,ProblemCode.CONFIGURATION_CONFLICT,"Configuration changed or already exists. Reload and review versions and revision times.");
            case INACTIVE -> response(HttpStatus.CONFLICT,ProblemCode.CONFIGURATION_INACTIVE,"This configuration is inactive. New operations are unavailable.");
            case INCOMPATIBLE_ZONES -> response(HttpStatus.CONFLICT,ProblemCode.INCOMPATIBLE_ZONES,"Both zones must declare the same temperature class.");
            case SCHEDULED_REVISIONS_PENDING -> response(HttpStatus.CONFLICT,ProblemCode.SCHEDULED_REVISIONS_PENDING,"Cancel all future scheduled costs before changing this lane's storage zones.");
            case NO_FUTURE_REVISIONS -> response(HttpStatus.CONFLICT,ProblemCode.NO_FUTURE_REVISIONS,"No future cost revisions remain to cancel. Reload the lane and reconcile its current history.");
            case ZONE_IMPACT_CHANGED -> response(HttpStatus.CONFLICT,ProblemCode.ZONE_IMPACT_CHANGED,"The zone or its lane impact changed. Review the latest impact before confirming archival.");
        };
    }
    private ResponseEntity<ApiProblem> response(HttpStatus status, ProblemCode code, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problems.create(status, code, detail));
    }
}
