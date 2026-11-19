package com.stockrescue.transfers.http;

import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.platform.http.ApiProblems;
import com.stockrescue.platform.http.ProblemCode;
import com.stockrescue.stores.domain.ConfigurationFailure;
import com.stockrescue.transfers.domain.TransferWriteConflict;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = TransferController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransferErrorHandler {
    private final ApiProblems problems;

    public TransferErrorHandler(ApiProblems problems) {
        this.problems = problems;
    }

    @ExceptionHandler(OrganizationUnavailableException.class)
    public ResponseEntity<ApiProblem> unavailable() {
        return response(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND, "The requested transfer or store context is unavailable.");
    }

    @ExceptionHandler(TransferWriteConflict.class)
    public ResponseEntity<ApiProblem> transferConflict() {
        return response(HttpStatus.CONFLICT, ProblemCode.INVENTORY_CONFLICT, "Transfer state changed. Reload and review the current request.");
    }

    @ExceptionHandler(InventoryFailure.class)
    public ResponseEntity<ApiProblem> inventoryFailure(InventoryFailure failure) {
        return switch (failure.kind()) {
            case INVALID_REQUEST -> response(HttpStatus.UNPROCESSABLE_CONTENT, ProblemCode.INVALID_REQUEST, "Review the transfer quantity, evidence and current context.");
            case LOT_NOT_ELIGIBLE -> response(HttpStatus.UNPROCESSABLE_CONTENT, ProblemCode.LOT_NOT_ELIGIBLE, "Current lot evidence does not permit this transfer.");
            case CONFLICT -> response(HttpStatus.CONFLICT, ProblemCode.INVENTORY_CONFLICT, "Stock changed or the reviewed source version is stale.");
            case INSUFFICIENT_STOCK -> response(HttpStatus.CONFLICT, ProblemCode.INSUFFICIENT_STOCK, "The transfer quantity exceeds current available stock.");
            case INACTIVE -> response(HttpStatus.CONFLICT, ProblemCode.INVENTORY_INACTIVE, "The product, lot or storage context is inactive.");
            case IDEMPOTENCY_CONFLICT -> response(HttpStatus.CONFLICT, ProblemCode.IDEMPOTENCY_CONFLICT, "This request identifier belongs to different reviewed values.");
            case DOCUMENT_ALREADY_RECEIVED -> response(HttpStatus.CONFLICT, ProblemCode.RECEIPT_ALREADY_RECORDED, "This document identity has already been used.");
            case ALLOCATION_UNAVAILABLE -> response(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_UNAVAILABLE, "The transfer reservation is no longer available in its exact quantity.");
            case POSITION_FROZEN -> response(HttpStatus.CONFLICT, ProblemCode.INVENTORY_FROZEN, "This stock position requires reconciliation before transfer.");
            case NOT_INITIALIZED -> response(HttpStatus.CONFLICT, ProblemCode.INVENTORY_NOT_INITIALIZED, "Physical stock has not been initialized for this position.");
        };
    }

    @ExceptionHandler(ConfigurationFailure.class)
    public ResponseEntity<ApiProblem> configuration(ConfigurationFailure failure) {
        return switch (failure.kind()) {
            case INVALID -> response(HttpStatus.UNPROCESSABLE_CONTENT, ProblemCode.INVALID_REQUEST, "Review the transfer's storage and route configuration.");
            case CONFLICT -> response(HttpStatus.CONFLICT, ProblemCode.CONFIGURATION_CONFLICT, "The route or storage context changed. Reload and review.");
            case INACTIVE -> response(HttpStatus.CONFLICT, ProblemCode.CONFIGURATION_INACTIVE, "The route, store, zone or location is inactive.");
            case INCOMPATIBLE_ZONES -> response(HttpStatus.CONFLICT, ProblemCode.INCOMPATIBLE_ZONES, "The route requires compatible storage zones.");
            case SCHEDULED_REVISIONS_PENDING -> response(HttpStatus.CONFLICT, ProblemCode.SCHEDULED_REVISIONS_PENDING, "The lane has scheduled revisions that must be resolved.");
            case NO_FUTURE_REVISIONS -> response(HttpStatus.CONFLICT, ProblemCode.NO_FUTURE_REVISIONS, "No future lane revision remains to cancel.");
            case ZONE_IMPACT_CHANGED -> response(HttpStatus.CONFLICT, ProblemCode.ZONE_IMPACT_CHANGED, "The reviewed zone impact changed.");
            case ZONE_HAS_ACTIVE_LOCATIONS -> response(HttpStatus.CONFLICT, ProblemCode.ZONE_HAS_ACTIVE_LOCATIONS, "Active storage locations prevent this zone change.");
        };
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiProblem> persistenceConflict(DataIntegrityViolationException failure) {
        return response(HttpStatus.CONFLICT, ProblemCode.CONFIGURATION_CONFLICT, "The transfer conflicts with current retained data.");
    }

    private ResponseEntity<ApiProblem> response(HttpStatus status, ProblemCode code, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problems.create(status, code, detail));
    }
}
