package com.stockrescue.inventory.http;

import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.inventory.domain.InventoryFailure;
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

@RestControllerAdvice(basePackages="com.stockrescue.inventory.http")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InventoryErrorHandler {
    private final ApiProblems problems;
    public InventoryErrorHandler(ApiProblems problems) { this.problems=problems; }
    @ExceptionHandler(OrganizationUnavailableException.class)
    public ResponseEntity<ApiProblem> unavailable() {
        return response(HttpStatus.NOT_FOUND,ProblemCode.RESOURCE_NOT_FOUND,"The requested resource is unavailable.");
    }
    @ExceptionHandler(InventoryFailure.class)
    public ResponseEntity<ApiProblem> failure(InventoryFailure error) {
        return switch(error.kind()) {
            case INVALID_REQUEST->response(HttpStatus.UNPROCESSABLE_CONTENT,ProblemCode.INVALID_REQUEST,"Review the quantity, document reference and required evidence.");
            case LOT_NOT_ELIGIBLE->response(HttpStatus.UNPROCESSABLE_CONTENT,ProblemCode.LOT_NOT_ELIGIBLE,"Current lot evidence does not permit this operation.");
            case CONFLICT->response(HttpStatus.CONFLICT,ProblemCode.INVENTORY_CONFLICT,"Stock changed or the requested quantity exceeds supported bounds. Reload and review.");
            case INSUFFICIENT_STOCK->response(HttpStatus.CONFLICT,ProblemCode.INSUFFICIENT_STOCK,"The requested quantity exceeds available stock.");
            case INACTIVE->response(HttpStatus.CONFLICT,ProblemCode.INVENTORY_INACTIVE,"The product, lot or storage context is inactive.");
            case IDEMPOTENCY_CONFLICT->response(HttpStatus.CONFLICT,ProblemCode.IDEMPOTENCY_CONFLICT,"This request identifier belongs to different reviewed values.");
            case DOCUMENT_ALREADY_RECEIVED->response(HttpStatus.CONFLICT,ProblemCode.RECEIPT_ALREADY_RECORDED,"This delivery document has already been recorded.");
            case ALLOCATION_UNAVAILABLE->response(HttpStatus.CONFLICT,ProblemCode.ALLOCATION_UNAVAILABLE,"The allocation is closed, has insufficient remaining quantity or belongs to a different bucket.");
            case POSITION_FROZEN->response(HttpStatus.CONFLICT,ProblemCode.INVENTORY_FROZEN,"This stock position requires reconciliation and authorized remediation.");
            case NOT_INITIALIZED->response(HttpStatus.CONFLICT,ProblemCode.INVENTORY_NOT_INITIALIZED,"Physical stock has not been initialized for this position.");
        };
    }
    private ResponseEntity<ApiProblem> response(HttpStatus status,ProblemCode code,String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problems.create(status,code,detail));
    }
}
