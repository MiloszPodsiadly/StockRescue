package com.stockrescue.catalog.http;

import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.catalog.domain.CatalogFailure;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.platform.http.ApiProblems;
import com.stockrescue.platform.http.ProblemCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses=ProductController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CatalogErrorHandler {
    private final ApiProblems problems;
    public CatalogErrorHandler(ApiProblems problems) { this.problems=problems; }
    @ExceptionHandler(OrganizationUnavailableException.class)
    public ResponseEntity<ApiProblem> unavailable() { return response(HttpStatus.NOT_FOUND,ProblemCode.RESOURCE_NOT_FOUND,"The requested resource is unavailable."); }
    @ExceptionHandler(CatalogFailure.class)
    public ResponseEntity<ApiProblem> failure(CatalogFailure failure) {
        return switch (failure.kind()) {
            case CONFLICT -> conflict();
            case INACTIVE -> response(HttpStatus.CONFLICT,ProblemCode.CATALOG_INACTIVE,"This catalog record or its parent is archived. Review saved history.");
            case GTIN_REVIEW_REQUIRED -> response(HttpStatus.CONFLICT,ProblemCode.GTIN_REVIEW_REQUIRED,
                    "Potential matches for this GTIN changed. Review the current catalog matches before creating this product.");
            case INVALID_STORAGE_CONTEXT -> response(HttpStatus.BAD_REQUEST,ProblemCode.INVALID_REQUEST,"Select one storage location or zone for evaluation.");
            case INVALID_SKU, INVALID_GTIN, UNSUPPORTED_UNIT, INVALID_PROVENANCE, INVALID_DATE_MARKING, INVALID_PRODUCT, INVALID_LOT, INVALID_SOURCE, INVALID_POLICY, INVALID_SAFETY_EVIDENCE ->
                    response(HttpStatus.UNPROCESSABLE_CONTENT,ProblemCode.INVALID_REQUEST,"Review catalog identity, check digit, unit and metadata.");
        };
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiProblem> conflict() { return response(HttpStatus.CONFLICT,ProblemCode.CATALOG_CONFLICT,"Catalog data changed or conflicts with saved data. Reload and review the request."); }
    private ResponseEntity<ApiProblem> response(HttpStatus status,ProblemCode code,String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problems.create(status,code,detail));
    }
}
