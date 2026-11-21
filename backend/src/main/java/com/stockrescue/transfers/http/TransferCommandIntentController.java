package com.stockrescue.transfers.http;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.transfers.application.TransferCommandIntentService;
import com.stockrescue.transfers.application.TransferCommandIntentService.FailureResolution;
import com.stockrescue.transfers.application.TransferCommandIntentService.InspectionStatus;
import com.stockrescue.transfers.application.TransferService.CommandOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value = "/api/v1/organizations/{organizationId}/transfer-command-intents", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "session")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid intent or request", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "403", description = "Current transfer action or CSRF denied", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "404", description = "Intent, organization or transfer unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "409", description = "Another unresolved intent exists or the intent is not in the required state", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
})
public class TransferCommandIntentController {
    private final TransferCommandIntentService intents;
    private final ObjectMapper json;

    public TransferCommandIntentController(TransferCommandIntentService intents, ObjectMapper json) {
        this.intents = intents;
        this.json = json;
    }

    @GetMapping
    @Operation(operationId = "listPendingTransferCommandIntents", summary = "List this actor's pending transfer command summaries")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = PendingIntentsResponse.class)))
    public ResponseEntity<PendingIntentsResponse> pending(Authentication actor, @PathVariable UUID organizationId) {
        var page = intents.pending(actor(actor), organizationId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PendingIntentsResponse(
                page.items().stream().map(IntentSummary::from).toList()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "prepareTransferCommandIntent", summary = "Save the exact actor-scoped transfer command before submitting it")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = IntentSummary.class)))
    public IntentSummary prepare(Authentication actor, @PathVariable UUID organizationId, @Valid @RequestBody PrepareRequest request) {
        return IntentSummary.from(intents.prepare(actor(actor), organizationId,
                new TransferCommandIntentService.PrepareRequest(request.prepareId(), request.operation(), request.transferId(),
                        json.valueToTree(request.payload()))));
    }

    @GetMapping("/{intentId}")
    @Operation(operationId = "inspectTransferCommandIntent", summary = "Inspect an actor-owned pending command and its committed outcome")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = IntentInspectionResponse.class)))
    public ResponseEntity<IntentInspectionResponse> inspect(Authentication actor, @PathVariable UUID organizationId,
            @PathVariable UUID intentId) {
        var inspection = intents.inspect(actor(actor), organizationId, intentId);
        TransferController.CommandResponse outcome = inspection.outcome() == null ? null
                : TransferController.CommandResponse.from(inspection.outcome());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new IntentInspectionResponse(
                inspection.id(), inspection.operation(), inspection.transferId(), inspection.requestId(), inspection.state(),
                inspection.preparedAt(), inspection.executionStatus(), inspection.payload(), outcome));
    }

    @PostMapping("/{intentId}/submitted")
    @Operation(operationId = "markTransferCommandIntentSubmitted", summary = "Record that the actor is about to submit the saved command")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = IntentSummary.class)))
    public IntentSummary submitted(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID intentId) {
        return IntentSummary.from(intents.markSubmitted(actor(actor), organizationId, intentId));
    }

    @PostMapping("/{intentId}/acknowledge")
    @Operation(operationId = "acknowledgeTransferCommandIntent", summary = "Resolve an intent after its transfer command receipt is visible")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = IntentSummary.class)))
    public IntentSummary acknowledge(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID intentId) {
        return IntentSummary.from(intents.acknowledge(actor(actor), organizationId, intentId));
    }

    @PostMapping(value = "/{intentId}/resolve-failure", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "resolveFailedTransferCommandIntent", summary = "Resolve a command only after a definite failure and no committed receipt")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = IntentSummary.class)))
    public IntentSummary resolveFailure(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID intentId,
            @Valid @RequestBody FailureRequest request) {
        return IntentSummary.from(intents.resolveFailure(actor(actor), organizationId, intentId, request.resolution()));
    }

    private static UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }

    public record PrepareRequest(@NotNull UUID prepareId, @NotBlank String operation, UUID transferId,
            @NotNull Map<String, Object> payload) { }
    public record FailureRequest(@NotNull FailureResolution resolution) { }
    @Schema(name = "TransferCommandIntentPage")
    public record PendingIntentsResponse(@NotNull List<@Valid IntentSummary> items) { }
    @Schema(name = "TransferCommandIntentSummary")
    public record IntentSummary(@NotNull UUID id, @NotBlank String operation, UUID transferId, @NotNull UUID requestId,
            @NotBlank String state, @NotNull Instant preparedAt) {
        static IntentSummary from(TransferCommandIntentService.Summary summary) {
            return new IntentSummary(summary.id(), summary.operation(), summary.transferId(), summary.requestId(), summary.state(), summary.preparedAt());
        }
    }
    @Schema(name = "TransferCommandIntentInspection")
    public record IntentInspectionResponse(@NotNull UUID id, @NotBlank String operation, UUID transferId,
            @NotNull UUID requestId, @NotBlank String state, @NotNull Instant preparedAt,
            @NotNull InspectionStatus executionStatus, @NotNull @Schema(type = "object") JsonNode payload,
            @Schema(types = {"object", "null"}) TransferController.CommandResponse committedOutcome) { }
}
