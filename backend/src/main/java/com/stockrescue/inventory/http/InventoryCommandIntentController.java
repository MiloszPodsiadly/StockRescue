package com.stockrescue.inventory.http;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stockrescue.inventory.application.InventoryCommandIntentService;
import com.stockrescue.inventory.application.InventoryCommands.ReceiptDocumentResult;
import com.stockrescue.inventory.application.InventoryCommands.Result;
import com.stockrescue.platform.http.ApiProblem;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value="/api/v1/organizations/{organizationId}/stores/{storeId}/inventory/command-intents",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="session")
@ApiResponses({
        @ApiResponse(responseCode="400",description="Invalid intent or request",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="401",description="Authentication required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="403",description="Current store action or CSRF denied",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="404",description="Intent, organization or store unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="409",description="Another unresolved intent exists or the intent is not in the required state",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
})
public class InventoryCommandIntentController {
    private final InventoryCommandIntentService intents;
    private final ObjectMapper json;

    public InventoryCommandIntentController(InventoryCommandIntentService intents,ObjectMapper json) {
        this.intents=intents;this.json=json;
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.Operation(operationId="listPendingInventoryCommandIntents",summary="List current-actor inventory intents without returning their private command payloads")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=PendingIntentsResponse.class)))
    public PendingIntentsResponse pending(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="100") int limit,
            @RequestParam(required=false) String subject) {
        var page=intents.pending(actor(actor),organizationId,storeId,offset,limit,subject);
        return new PendingIntentsResponse(page.items().stream().map(IntentSummary::from).toList(),page.hasMore());
    }

    @GetMapping("/{intentId}")
    @io.swagger.v3.oas.annotations.Operation(operationId="inspectInventoryCommandIntent",summary="Inspect the exact private command payload and any committed outcome before recovery")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=IntentInspectionResponse.class)))
    public ResponseEntity<IntentInspectionResponse> inspect(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID intentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(IntentInspectionResponse.from(intents.inspect(actor(actor),organizationId,storeId,intentId)));
    }

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.Operation(operationId="prepareInventoryCommandIntent",summary="Durably prepare one validated stock command before execution")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=IntentSummary.class)))
    public IntentSummary prepare(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @Valid @RequestBody PrepareRequest request) {
        return IntentSummary.from(intents.prepare(actor(actor),organizationId,storeId,new InventoryCommandIntentService.PrepareRequest(
                request.prepareId(),request.operation().name(),json.valueToTree(request.payload()))));
    }

    @PostMapping("/{intentId}/execute")
    @io.swagger.v3.oas.annotations.Operation(operationId="executeInventoryCommandIntent",summary="Run the exact prepared command through current inventory authorization and ledger services")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=ExecutionResponse.class)))
    public ExecutionResponse execute(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,@PathVariable UUID intentId) {
        var execution=intents.execute(actor(actor),organizationId,storeId,intentId);
        Object outcome=execution.outcome();
        if(outcome instanceof Result result) outcome=InventoryController.InventoryCommandResponse.from(result);
        else if(outcome instanceof ReceiptDocumentResult result) outcome=InventoryController.ReceiptDocumentResponse.from(result);
        return new ExecutionResponse(execution.id(),execution.operation(),execution.requestId(),outcome);
    }

    @PostMapping("/{intentId}/acknowledge")
    @io.swagger.v3.oas.annotations.Operation(operationId="acknowledgeInventoryCommandIntent",summary="Acknowledge an intent only after its command receipt has committed")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=IntentSummary.class)))
    public IntentSummary acknowledge(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,@PathVariable UUID intentId) {
        return IntentSummary.from(intents.acknowledge(actor(actor),organizationId,storeId,intentId));
    }

    @PostMapping("/{intentId}/review-rejected")
    @io.swagger.v3.oas.annotations.Operation(operationId="reviewRejectedInventoryCommandIntent",summary="Close a visibly rejected draft after confirming it has no committed command receipt")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=IntentSummary.class)))
    public IntentSummary reviewRejected(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,@PathVariable UUID intentId) {
        return IntentSummary.from(intents.reviewRejected(actor(actor),organizationId,storeId,intentId));
    }

    private static UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }

    public record PrepareRequest(@NotNull UUID prepareId,@NotNull InventoryCommandIntentService.Operation operation,
            @NotNull Map<String,Object> payload) { }
    public record PendingIntentsResponse(@NotNull List<@Valid IntentSummary> items,boolean hasMore) { }
    public record IntentSummary(@NotNull UUID id,@NotBlank String operation,@NotBlank String subject,@NotNull UUID requestId,
            @NotBlank String state,@NotNull Instant preparedAt) {
        static IntentSummary from(InventoryCommandIntentService.Summary summary) {
            return new IntentSummary(summary.id(),summary.operation(),summary.subject(),summary.requestId(),summary.state(),summary.preparedAt());
        }
    }
    public record IntentInspectionResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String operation,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String subject,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String state,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant preparedAt,
            @Schema(types={"string","null"},format="date-time",requiredMode=Schema.RequiredMode.REQUIRED) Instant executedAt,
            @Schema(allowableValues={"PREPARED","COMMITTED_AWAITING_ACKNOWLEDGEMENT","ACKNOWLEDGED","REVIEWED","CONFLICT"},
                    requiredMode=Schema.RequiredMode.REQUIRED) InventoryCommandIntentService.InspectionStatus executionStatus,
            @Schema(types={"object","null"},requiredMode=Schema.RequiredMode.REQUIRED) InventoryCommandIntentService.PositionPayload positionPreview,
            @Schema(types={"object","null"},requiredMode=Schema.RequiredMode.REQUIRED) InventoryCommandIntentService.ReceiptPayload receiptDocumentPreview,
            @Schema(types={"object","null"},requiredMode=Schema.RequiredMode.REQUIRED) InventoryController.InventoryCommandResponse committedOutcome,
            @Schema(types={"object","null"},requiredMode=Schema.RequiredMode.REQUIRED) InventoryController.ReceiptDocumentResponse committedReceiptOutcome) {
        static IntentInspectionResponse from(InventoryCommandIntentService.Inspection inspection) {
            var outcome=inspection.committedOutcome()==null?null:InventoryController.InventoryCommandResponse.from(inspection.committedOutcome());
            var receiptOutcome=inspection.committedReceiptOutcome()==null?null:
                    InventoryController.ReceiptDocumentResponse.from(inspection.committedReceiptOutcome());
            return new IntentInspectionResponse(inspection.id(),inspection.operation(),inspection.subject(),inspection.requestId(),
                    inspection.state(),inspection.preparedAt(),inspection.executedAt(),inspection.executionStatus(),inspection.positionPreview(),
                    inspection.receiptDocumentPreview(),outcome,receiptOutcome);
        }
    }
    public record ExecutionResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String operation,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID requestId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,oneOf={InventoryController.InventoryCommandResponse.class,InventoryController.ReceiptDocumentResponse.class}) Object outcome) { }
}
