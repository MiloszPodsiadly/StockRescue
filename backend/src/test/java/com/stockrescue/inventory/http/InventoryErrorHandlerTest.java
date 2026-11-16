package com.stockrescue.inventory.http;

import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.inventory.domain.InventoryFailure.Kind;
import com.stockrescue.platform.http.ApiProblems;
import com.stockrescue.platform.http.ProblemCode;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryErrorHandlerTest {
    private final InventoryErrorHandler handler=new InventoryErrorHandler(new ApiProblems(new ObjectMapper()));
    @Test void anIneligibleLotHasAnExplicitUnprocessableBusinessResult() {
        var response=handler.failure(new InventoryFailure(Kind.LOT_NOT_ELIGIBLE));
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().code()).isEqualTo(ProblemCode.LOT_NOT_ELIGIBLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }
    @Test void quantityVersionIdempotencyAndAllocationConflictsHaveStableConflictResponses() {
        for(var kind:Kind.values()) {
            var response=handler.failure(new InventoryFailure(kind));
            assertThat(response.getStatusCode().value()).as(kind.name()).isEqualTo(kind==Kind.INVALID_REQUEST || kind==Kind.LOT_NOT_ELIGIBLE?422:409);
        }
        assertThat(handler.failure(new InventoryFailure(Kind.IDEMPOTENCY_CONFLICT)).getBody().code()).isEqualTo(ProblemCode.IDEMPOTENCY_CONFLICT);
        assertThat(handler.failure(new InventoryFailure(Kind.DOCUMENT_ALREADY_RECEIVED)).getBody().code()).isEqualTo(ProblemCode.RECEIPT_ALREADY_RECORDED);
    }
    @Test void aScopedLookupFailureConcealsIdentityAndPreservesCorrelation() {
        try(var ignored=MDC.putCloseable("correlationId","inventory-error-check")) {
            var response=handler.unavailable();
            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody().code()).isEqualTo(ProblemCode.RESOURCE_NOT_FOUND);
            assertThat(response.getBody().correlationId()).isEqualTo("inventory-error-check");
        }
    }
}
