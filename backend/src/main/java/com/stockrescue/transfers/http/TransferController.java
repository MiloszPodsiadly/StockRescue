package com.stockrescue.transfers.http;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.platform.http.ApiProblem;
import com.stockrescue.transfers.application.TransferService;
import com.stockrescue.transfers.application.TransferQueryService;
import com.stockrescue.transfers.application.TransferService.CommandOutcome;
import com.stockrescue.transfers.application.TransferService.CreateLine;
import com.stockrescue.transfers.application.TransferService.CreateRequest;
import com.stockrescue.transfers.application.TransferService.CancelRequest;
import com.stockrescue.transfers.application.TransferService.DecisionRequest;
import com.stockrescue.transfers.application.TransferService.PickRequest;
import com.stockrescue.transfers.application.TransferService.DispatchRequest;
import com.stockrescue.transfers.application.TransferService.DispatchLine;
import com.stockrescue.transfers.application.TransferService.ReceiveRequest;
import com.stockrescue.transfers.application.TransferService.ReceiveLine;
import com.stockrescue.transfers.application.TransferService.DamageRequest;
import com.stockrescue.transfers.application.TransferService.DamageLine;
import com.stockrescue.transfers.application.TransferService.DiscrepancyRequest;
import com.stockrescue.transfers.application.TransferService.RequestReturn;
import com.stockrescue.transfers.application.TransferService.ReturnRequestLine;
import com.stockrescue.transfers.application.TransferService.ConfirmReturnRequest;
import com.stockrescue.transfers.application.TransferService.ConfirmReturnLine;
import com.stockrescue.transfers.application.TransferService.Recovery;
import com.stockrescue.transfers.domain.TransferMovementType;
import com.stockrescue.transfers.domain.TransferQuantities;
import com.stockrescue.transfers.domain.TransferStatus;
import com.stockrescue.transfers.persistence.TransferRepository.TransferDetail;
import com.stockrescue.transfers.persistence.TransferRepository.TransferLine;
import com.stockrescue.transfers.persistence.TransferRepository.TransferSummary;
import com.stockrescue.transfers.persistence.TransferRepository.CursorPage;
import com.stockrescue.transfers.persistence.TransferRepository.Page;
import com.stockrescue.transfers.persistence.TransferRepository.CustodyMovement;
import com.stockrescue.transfers.persistence.TransferRepository.InTransitLine;
import com.stockrescue.transfers.persistence.TransferRepository.Discrepancy;
import com.stockrescue.transfers.persistence.TransferRepository.ActiveReturn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value = "/api/v1/organizations/{organizationId}/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "session")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid transfer request", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "403", description = "Current transfer action or CSRF denied", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "404", description = "Transfer or current store context unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "409", description = "Stale state, stock conflict or changed retry payload", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "422", description = "Invalid evidence, stock safety or transfer context", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
})
public class TransferController {
    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @GetMapping
    @Operation(operationId = "listTransfers", summary = "List transfers for one currently authorized store with optional lifecycle filtering",
            description = "The optional cursor is bound to the organization, store, and status filter. It orders the current mutable queue by updatedAt descending and transfer ID descending; refresh from the first page to see records whose updatedAt changed while paging.")
    @ApiResponse(responseCode = "200", description = "Current transfer queue", content = @Content(schema = @Schema(implementation = TransferQueuePage.class)))
    public ResponseEntity<TransferQueuePage> list(Authentication actor, @PathVariable UUID organizationId,
            @RequestParam UUID storeId, @RequestParam(required = false) TransferStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @Parameter(description = "Opaque cursor for this organization, store, and status filter")
            @RequestParam(required = false) @Size(max = 512) String cursor) {
        UUID actorId = actor(actor);
        var page = cursor != null || offset == 0
                ? TransferQueuePage.from(transfers.page(actorId, organizationId, storeId, status, cursor, limit))
                : TransferQueuePage.from(transfers.page(actorId, organizationId, storeId, status, offset, limit));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createTransfer", summary = "Create an auditable stock transfer request without changing stock")
    @ApiResponse(responseCode = "200", description = "Transfer request recorded or exact retry recovered", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse create(Authentication actor, @PathVariable UUID organizationId,
            @Valid @RequestBody CreateTransferRequest request) {
        return CommandResponse.from(transfers.create(actor(actor), organizationId, new CreateRequest(request.requestId(),
                request.sourceStoreId(), request.destinationStoreId(), request.lines().stream().map(line ->
                        new CreateLine(line.sourcePositionId(), line.destinationLocationId(), line.quantity(),
                                line.expectedSourceVersion())).toList(), request.reason(), request.evidenceReference())));
    }

    @PostMapping(value = "/{transferId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "approveTransfer", summary = "Approve a transfer and reserve its current eligible source stock exactly once")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse approve(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID transferId,
            @Valid @RequestBody TransferDecisionRequest request) {
        return CommandResponse.from(transfers.approve(actor(actor), organizationId, transferId, request.toService()));
    }

    @PostMapping(value = "/{transferId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "rejectTransfer", summary = "Reject an unapproved transfer request with immutable reason and evidence")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse reject(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID transferId,
            @Valid @RequestBody TransferDecisionRequest request) {
        return CommandResponse.from(transfers.reject(actor(actor), organizationId, transferId, request.toService()));
    }

    @PostMapping(value = "/{transferId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "cancelTransfer", summary = "Cancel a pre-dispatch transfer, document any physical put-back and release its exact active reservation")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse cancel(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID transferId,
            @Valid @RequestBody TransferCancelRequest request) {
        return CommandResponse.from(transfers.cancel(actor(actor), organizationId, transferId, request.toService()));
    }

    @PostMapping(value = "/{transferId}/start-pick", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "startTransferPick", summary = "Begin source picking after rechecking current stock and route eligibility")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse startPick(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID transferId,
            @Valid @RequestBody TransferDecisionRequest request) {
        return CommandResponse.from(transfers.startPick(actor(actor), organizationId, transferId, request.toService()));
    }

    @PostMapping(value = "/{transferId}/pick", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "recordTransferPick", summary = "Record one complete picked transfer line with scanned SKU and lot")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse recordPick(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID transferId,
            @Valid @RequestBody TransferPickRequest request) {
        return CommandResponse.from(transfers.recordPick(actor(actor), organizationId, transferId, request.toService()));
    }

    @PostMapping(value = "/{transferId}/dispatch", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "dispatchTransfer", summary = "Atomically dispatch every picked line from source stock into transfer custody")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse dispatch(Authentication actor, @PathVariable UUID organizationId, @PathVariable UUID transferId,
            @Valid @RequestBody TransferDispatchRequest request) {
        return CommandResponse.from(transfers.dispatch(actor(actor), organizationId, transferId, request.toService()));
    }

    @PostMapping(value = "/{transferId}/receive", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "receiveTransfer", summary = "Receive one or more whole or partial transfer lines into eligible stock or quarantine")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse receive(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID transferId,
            @Valid @RequestBody TransferReceiveRequest request) {
        return CommandResponse.from(transfers.receive(actor(actor),organizationId,transferId,request.toService()));
    }

    @PostMapping(value = "/{transferId}/open-discrepancy", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "openTransferDiscrepancy", summary = "Record an evidenced discrepancy without changing stock or custody quantities")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse openDiscrepancy(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID transferId,
            @Valid @RequestBody TransferDiscrepancyRequest request) {
        return CommandResponse.from(transfers.openDiscrepancy(actor(actor),organizationId,transferId,request.toService()));
    }

    @PostMapping(value = "/{transferId}/damage", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "recordTransferDamage", summary = "Confirm evidenced in-transit loss without recording a physical destination receipt")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse damage(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID transferId,
            @Valid @RequestBody TransferDamageRequest request) {
        return CommandResponse.from(transfers.recordDamage(actor(actor),organizationId,transferId,request.toService()));
    }

    @PostMapping(value = "/{transferId}/request-return", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "requestTransferReturn", summary = "Request return of each selected line's remaining quantity without settling transit")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse requestReturn(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID transferId,
            @Valid @RequestBody TransferReturnRequest request) {
        return CommandResponse.from(transfers.requestReturn(actor(actor),organizationId,transferId,request.toService()));
    }

    @PostMapping(value = "/{transferId}/confirm-return", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "confirmTransferReturn", summary = "Confirm physical return at the source and post stock with current safety disposition")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse confirmReturn(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID transferId,
            @Valid @RequestBody TransferConfirmReturnRequest request) {
        return CommandResponse.from(transfers.confirmReturn(actor(actor),organizationId,transferId,request.toService()));
    }

    @PostMapping(value = "/{transferId}/close-discrepancy", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "closeTransferDiscrepancy", summary = "Confirm reconciliation after every line has zero outstanding custody")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CommandResponse.class)))
    public CommandResponse closeDiscrepancy(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID transferId,
            @Valid @RequestBody TransferDecisionRequest request) {
        return CommandResponse.from(transfers.closeDiscrepancy(actor(actor),organizationId,transferId,request.toService()));
    }

    @GetMapping("/{transferId}")
    @Operation(operationId = "getTransfer", summary = "Read a transfer visible through a current grant to either participating store")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TransferResponse.class)))
    public ResponseEntity<TransferResponse> detail(Authentication actor, @PathVariable UUID organizationId,
            @PathVariable UUID transferId) {
        UUID actorId = actor(actor);
        TransferDetail detail = transfers.detail(actorId, organizationId, transferId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(TransferResponse.from(detail, transfers.canCancel(actorId, organizationId, transferId)));
    }

    @GetMapping("/{transferId}/capabilities")
    @Operation(operationId = "getTransferCapabilities", summary = "Read current actions for an authorized transfer")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TransferCapabilitiesResponse.class)))
    public ResponseEntity<TransferCapabilitiesResponse> capabilities(Authentication actor,
            @PathVariable UUID organizationId, @PathVariable UUID transferId) {
        var value = transfers.capabilities(actor(actor), organizationId, transferId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new TransferCapabilitiesResponse(value.sourceActions(), value.destinationActions(),
                        value.canReceivePhysicalSettlement(), value.canConfirmPhysicalReturn(),
                        value.historicalSettlementActions()));
    }

    @GetMapping("/{transferId}/settlement-positions")
    @Operation(operationId = "getTransferSettlementPositions", summary = "Read only the position versions needed to settle an archived transfer")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TransferSettlementPositionsResponse.class)))
    public ResponseEntity<TransferSettlementPositionsResponse> settlementPositions(Authentication actor,
            @PathVariable UUID organizationId, @PathVariable UUID transferId) {
        var value = transfers.settlementPositions(actor(actor), organizationId, transferId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new TransferSettlementPositionsResponse(value.sourcePositions().stream().map(TransferSettlementPositionVersion::from).toList(),
                        value.destinationPositions().stream().map(TransferSettlementPositionVersion::from).toList()));
    }

    @GetMapping("/{transferId}/custody")
    @Operation(operationId = "getTransferCustodyTimeline", summary = "Read the immutable custody movements for a transfer",
            description = "Pages use a scope-bound keyset cursor ordered by recordedAt descending and movement ID descending. Continue while hasMore is true; the cursor is not an authorization token.")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TransferCustodyPage.class)))
    public ResponseEntity<TransferCustodyPage> custody(Authentication actor,@PathVariable UUID organizationId,
            @PathVariable UUID transferId,@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="50") int limit,
            @Parameter(description = "Opaque cursor for this organization and transfer")
            @RequestParam(required=false) @Size(max=512) String cursor) {
        UUID actorId=actor(actor);
        var page=cursor!=null || offset==0
                ?TransferCustodyPage.from(transfers.custodyTimeline(actorId,organizationId,transferId,cursor,limit))
                :TransferCustodyPage.from(transfers.custodyTimeline(actorId,organizationId,transferId,offset,limit));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    @GetMapping("/{transferId}/active-returns")
    @Operation(operationId = "listActiveTransferReturns", summary = "Read current unsettled physical return requests for one authorized transfer")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TransferActiveReturnPage.class)))
    public ResponseEntity<TransferActiveReturnPage> activeReturns(Authentication actor,
            @PathVariable UUID organizationId, @PathVariable UUID transferId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new TransferActiveReturnPage(
                transfers.activeReturns(actor(actor), organizationId, transferId).stream()
                        .map(TransferActiveReturn::from).toList()));
    }

    @GetMapping("/in-transit")
    @Operation(operationId = "listTransfersInTransit", summary = "List line-wise in-transit quantities and age for a currently authorized store",
            description = "The optional scope-bound cursor follows persisted dispatch time, transfer ID, and line ID. Outstanding lines can leave the mutable view after settlement; refresh from the first page for current state.")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TransferInTransitPage.class)))
    public ResponseEntity<TransferInTransitPage> inTransit(Authentication actor,@PathVariable UUID organizationId,
            @RequestParam UUID storeId,@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="50") int limit,
            @Parameter(description = "Opaque cursor for this organization and store")
            @RequestParam(required=false) @Size(max=512) String cursor) {
        UUID actorId=actor(actor);
        var page=cursor!=null || offset==0
                ?TransferInTransitPage.from(transfers.inTransitAging(actorId,organizationId,storeId,cursor,limit))
                :TransferInTransitPage.from(transfers.inTransitAging(actorId,organizationId,storeId,offset,limit));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    @GetMapping("/discrepancies")
    @Operation(operationId = "listTransferDiscrepancies", summary = "List documented discrepancies and return-request lifecycle evidence for a currently authorized store",
            description = "Pages use a scope-bound keyset cursor ordered by recordedAt descending and evidence ID descending. Continue while hasMore is true; immutable records inserted after paging starts are visible after refreshing the first page.")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TransferDiscrepancyPage.class)))
    public ResponseEntity<TransferDiscrepancyPage> discrepancies(Authentication actor,@PathVariable UUID organizationId,
            @RequestParam UUID storeId,@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="50") int limit,
            @Parameter(description = "Opaque cursor for this organization and store")
            @RequestParam(required=false) @Size(max=512) String cursor) {
        UUID actorId=actor(actor);
        var page=cursor!=null || offset==0
                ?TransferDiscrepancyPage.from(transfers.discrepancies(actorId,organizationId,storeId,cursor,limit))
                :TransferDiscrepancyPage.from(transfers.discrepancies(actorId,organizationId,storeId,offset,limit));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    @GetMapping("/{transferId}/in-transit")
    @Operation(operationId="listTransferScopedTransit",summary="Read in-transit lines filtered to one currently authorized transfer",
            description="The query filters by organization and transfer before pagination. Its scope-bound cursor follows persisted dispatch time, transfer ID, and line ID; current authorization for a participant store is checked on every request.")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=TransferInTransitPage.class)))
    public ResponseEntity<TransferInTransitPage> transferInTransit(Authentication actor,@PathVariable UUID organizationId,
            @PathVariable UUID transferId,
            @Parameter(description = "Opaque cursor for this organization and transfer")
            @RequestParam(required=false) @Size(max=512) String cursor,
            @RequestParam(defaultValue="50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(TransferInTransitPage.from(
                transfers.inTransit(actor(actor),organizationId,transferId,cursor,limit)));
    }

    @GetMapping("/{transferId}/discrepancies")
    @Operation(operationId="listTransferScopedDiscrepancies",summary="Read discrepancy evidence filtered to one currently authorized transfer",
            description="The query filters by organization and transfer before pagination. Pages use immutable recordedAt-descending, evidence-ID-descending keyset order; current authorization for a participant store is checked on every request.")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=TransferDiscrepancyPage.class)))
    public ResponseEntity<TransferDiscrepancyPage> transferDiscrepancies(Authentication actor,@PathVariable UUID organizationId,
            @PathVariable UUID transferId,
            @Parameter(description = "Opaque cursor for this organization and transfer")
            @RequestParam(required=false) @Size(max=512) String cursor,
            @RequestParam(defaultValue="50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(TransferDiscrepancyPage.from(
                transfers.transferDiscrepancies(actor(actor),organizationId,transferId,cursor,limit)));
    }

    @GetMapping("/commands/{operation}/{requestId}")
    @Operation(operationId = "recoverTransferCommand", summary = "Read the original outcome of a command without repeating a stock movement")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RecoveryResponse.class)))
    public ResponseEntity<RecoveryResponse> recover(Authentication actor, @PathVariable UUID organizationId,
            @PathVariable String operation, @PathVariable UUID requestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(RecoveryResponse.from(transfers.recover(actor(actor), organizationId, operation, requestId)));
    }

    private UUID actor(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    public record CreateTransferRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceStoreId,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID destinationStoreId,
            @NotNull @Size(min = 1, max = TransferService.MAX_LINES) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<@Valid CreateTransferLineRequest> lines,
            @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason,
            @NotBlank @Size(max = 256) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String evidenceReference) { }

    public record CreateTransferLineRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourcePositionId,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID destinationLocationId,
            @NotNull @Positive @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long quantity,
            @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long expectedSourceVersion) { }

    public record TransferDecisionRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long expectedVersion,
            @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason,
            @NotBlank @Size(max = 256) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String evidenceReference) {
        DecisionRequest toService() { return new DecisionRequest(requestId, expectedVersion, reason, evidenceReference); }
    }

    public record TransferCancelRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long expectedVersion,
            @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason,
            @NotBlank @Size(max = 256) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String evidenceReference,
            @Schema(description = "Required and true after physically returning every picked line to its source shelf",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED) Boolean putBackConfirmed,
            @Size(max = 200) @Schema(description = "Physical put-back record reference, required when any line was picked",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED) String putBackDocumentReference) {
        CancelRequest toService() {
            return new CancelRequest(requestId, expectedVersion, reason, evidenceReference,
                    putBackConfirmed, putBackDocumentReference);
        }
    }

    public record TransferPickRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long expectedVersion,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID lineId,
            @NotBlank @Size(max = 200) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String documentReference,
            @NotNull @Positive @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long quantity,
            @NotBlank @Size(max = 64) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String observedSku,
            @NotBlank @Size(max = 128) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String observedLotCode,
            @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason,
            @NotBlank @Size(max = 256) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String evidenceReference) {
        PickRequest toService() { return new PickRequest(requestId, expectedVersion, lineId, documentReference,
                quantity, observedSku, observedLotCode, reason, evidenceReference); }
    }

    public record TransferDispatchRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
            @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long expectedVersion,
            @NotBlank @Size(max = 200) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String documentReference,
            @NotNull @Size(min = 1, max = TransferService.MAX_LINES) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<@Valid TransferDispatchLineRequest> lines,
            @NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason,
            @NotBlank @Size(max = 256) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String evidenceReference) {
        DispatchRequest toService() { return new DispatchRequest(requestId, expectedVersion, documentReference,
                lines.stream().map(line -> new DispatchLine(line.lineId(), line.quantity(), line.observedSku(),
                        line.observedLotCode())).toList(), reason, evidenceReference); }
    }

    public record TransferDispatchLineRequest(@NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID lineId,
            @NotNull @Positive @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long quantity,
            @NotBlank @Size(max = 64) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String observedSku,
            @NotBlank @Size(max = 128) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String observedLotCode) { }

    public record TransferReceiveRequest(@NotNull UUID requestId,@NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long expectedVersion,
            @NotBlank @Size(max = 200) String documentReference,@NotNull Instant physicallyReceivedAt,
            @NotNull @Size(min=1,max=TransferService.MAX_LINES) List<@Valid TransferReceiveLineRequest> lines,
            @NotBlank @Size(max=500) String reason,@NotBlank @Size(max=256) String evidenceReference) {
        ReceiveRequest toService() { return new ReceiveRequest(requestId,expectedVersion,documentReference,physicallyReceivedAt,
                lines.stream().map(line -> new ReceiveLine(line.lineId(),line.quantity(),line.expectedDestinationVersion(),
                        line.documentLineReference(),line.observedSku(),line.observedLotCode())).toList(),reason,evidenceReference); }
    }

    public record TransferReceiveLineRequest(@NotNull UUID lineId,@NotNull @Positive Long quantity,
            @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long expectedDestinationVersion,
            @NotBlank @Size(max=64) String documentLineReference,@NotBlank @Size(max=64) String observedSku,
            @NotBlank @Size(max=128) String observedLotCode) { }

    public record TransferDiscrepancyRequest(@NotNull UUID requestId,@NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long expectedVersion,
            UUID lineId,@NotBlank @Size(max=500) String expectedState,@NotBlank @Size(max=500) String observedState,
            @NotBlank @Size(max=500) String reason,@NotBlank @Size(max=256) String evidenceReference) {
        DiscrepancyRequest toService() { return new DiscrepancyRequest(requestId,expectedVersion,lineId,expectedState,
                observedState,reason,evidenceReference); }
    }

    public record TransferDamageRequest(@NotNull UUID requestId,@NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long expectedVersion,
            @NotBlank @Size(max=200) String documentReference,
            @NotBlank @Pattern(regexp="DAMAGED_IN_TRANSIT|DESTROYED_IN_TRANSIT|LOST_WITH_EVIDENCE") String reasonCode,
            @NotNull @Size(min=1,max=TransferService.MAX_LINES) List<@Valid TransferDamageLineRequest> lines,
            @NotBlank @Size(max=500) String reason,@NotBlank @Size(max=256) String evidenceReference) {
        DamageRequest toService() { return new DamageRequest(requestId,expectedVersion,documentReference,reasonCode,
                lines.stream().map(line -> new DamageLine(line.lineId(),line.quantity(),line.documentLineReference())).toList(),
                reason,evidenceReference); }
    }

    public record TransferDamageLineRequest(@NotNull UUID lineId,@NotNull @Positive Long quantity,
            @NotBlank @Size(max=64) String documentLineReference) { }

    public record TransferReturnRequest(@NotNull UUID requestId,@NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long expectedVersion,
            @NotBlank @Size(max=200) String requestReference,@NotNull @Size(min=1,max=TransferService.MAX_LINES)
            List<@Valid TransferReturnRequestLine> lines,@NotBlank @Size(max=500) String reason,
            @NotBlank @Size(max=256) String evidenceReference) {
        RequestReturn toService() { return new RequestReturn(requestId,expectedVersion,requestReference,
                lines.stream().map(line -> new ReturnRequestLine(line.lineId())).toList(),reason,evidenceReference); }
    }

    public record TransferReturnRequestLine(@NotNull UUID lineId) { }

    public record TransferConfirmReturnRequest(@NotNull UUID requestId,@NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long expectedVersion,
            @NotBlank @Size(max=200) String requestReference,@NotBlank @Size(max=200) String documentReference,
            @NotNull @Size(min=1,max=TransferService.MAX_LINES)
            List<@Valid TransferConfirmReturnLineRequest> lines,@NotBlank @Size(max=500) String reason,
            @NotBlank @Size(max=256) String evidenceReference) {
        ConfirmReturnRequest toService() { return new ConfirmReturnRequest(requestId,expectedVersion,requestReference,documentReference,
                lines.stream().map(line -> new ConfirmReturnLine(line.lineId(),line.quantity(),line.expectedSourceVersion(),
                        line.documentLineReference(),line.observedSku(),line.observedLotCode())).toList(),reason,evidenceReference); }
    }

    public record TransferConfirmReturnLineRequest(@NotNull UUID lineId,
            @NotNull @Positive Long quantity,
            @NotNull @Min(0) @Max(Long.MAX_VALUE - 1) Long expectedSourceVersion,
            @NotBlank @Size(max=64) String documentLineReference,@NotBlank @Size(max=64) String observedSku,
            @NotBlank @Size(max=128) String observedLotCode) { }

    public record TransferCustodyPage(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TransferCustodyMovement> items,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED,description="Cursor for the next immutable custody page, or null when complete") String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,description="True when another page exists") boolean hasMore) {
        static TransferCustodyPage from(Page<CustodyMovement> page) {
            return new TransferCustodyPage(page.items().stream().map(TransferCustodyMovement::from).toList(),null,page.hasMore());
        }
        static TransferCustodyPage from(CursorPage<CustodyMovement> page) {
            return new TransferCustodyPage(page.items().stream().map(TransferCustodyMovement::from).toList(),page.nextCursor(),page.hasMore());
        }
    }

    public record TransferInTransitPage(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TransferInTransitLine> items,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED,description="Cursor for the next line page, or null when complete") String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,description="True when another page exists") boolean hasMore) {
        static TransferInTransitPage from(Page<InTransitLine> page) {
            return new TransferInTransitPage(page.items().stream().map(TransferInTransitLine::from).toList(),null,page.hasMore());
        }
        static TransferInTransitPage from(CursorPage<InTransitLine> page) {
            return new TransferInTransitPage(page.items().stream().map(TransferInTransitLine::from).toList(),page.nextCursor(),page.hasMore());
        }
    }

    public record TransferDiscrepancyPage(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TransferDiscrepancyRecord> items,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED,description="Cursor for the next immutable evidence page, or null when complete") String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,description="True when another page exists") boolean hasMore) {
        static TransferDiscrepancyPage from(Page<Discrepancy> page) {
            return new TransferDiscrepancyPage(page.items().stream().map(TransferDiscrepancyRecord::from).toList(),null,page.hasMore());
        }
        static TransferDiscrepancyPage from(CursorPage<Discrepancy> page) {
            return new TransferDiscrepancyPage(page.items().stream().map(TransferDiscrepancyRecord::from).toList(),page.nextCursor(),page.hasMore());
        }
    }

    public record TransferCustodyMovement(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID lineId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TransferMovementType type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID commandId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID actorId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long quantity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long outstandingBefore,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long outstandingAfter,
            @Schema(types = {"string", "null"}, format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID stockStoreId,
            @Schema(types = {"string", "null"}, format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID stockMovementId,
            @Schema(types = {"string", "null"}, format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID stockPositionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String evidenceReference,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant recordedAt) {
        static TransferCustodyMovement from(CustodyMovement value) {
            return new TransferCustodyMovement(value.id(), value.lineId(), value.version(), value.type(), value.commandId(),
                    value.actorId(), value.quantity(), value.outstandingBefore(), value.outstandingAfter(), value.stockStoreId(),
                    value.stockMovementId(), value.stockPositionId(), value.reason(), value.evidenceReference(), value.recordedAt());
        }
    }

    public record TransferInTransitLine(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID transferId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID lineId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceStoreId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID destinationStoreId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourcePositionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID destinationLocationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long outstanding,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant dispatchedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long ageSeconds) {
        static TransferInTransitLine from(InTransitLine value) {
            return new TransferInTransitLine(value.transferId(), value.lineId(), value.sourceStoreId(), value.destinationStoreId(),
                    value.sourcePositionId(), value.destinationLocationId(), value.outstanding(), value.dispatchedAt(), value.ageSeconds());
        }
    }

    public record TransferDiscrepancyRecord(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID transferId,
            @Schema(types = {"string", "null"}, format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID lineId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String evidenceReference,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String expectedState,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String observedState,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID actorId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"OPEN", "LINE_SETTLED", "TRANSFER_CLOSED", "PENDING", "PARTIALLY_FULFILLED", "FULFILLED", "SUPERSEDED"}) String resolutionScope,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean lineSettled,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean transferClosed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant recordedAt) {
        static TransferDiscrepancyRecord from(Discrepancy value) {
            return new TransferDiscrepancyRecord(value.id(), value.transferId(), value.lineId(), value.type(), value.reason(),
                    value.evidenceReference(), value.expectedState(), value.observedState(), value.actorId(),
                    value.resolutionScope(),value.lineSettled(),value.transferClosed(),value.recordedAt());
        }
    }

    public record TransferActiveReturnPage(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TransferActiveReturn> items) { }

    public record TransferActiveReturn(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID transferId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID lineId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID evidenceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String requestReference,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long requestedQuantity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long settledQuantity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long unsettledQuantity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String state,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long transferVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant requestedAt) {
        static TransferActiveReturn from(ActiveReturn value) {
            return new TransferActiveReturn(value.transferId(), value.lineId(), value.evidenceId(), value.requestId(),
                    value.requestReference(), value.requestedQuantity(), value.settledQuantity(), value.unsettledQuantity(),
                    value.state(), value.transferVersion(), value.requestedAt());
        }
    }

    public record TransferQueuePage(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TransferQueueItem> items,
            @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Cursor for the next current queue page, or null when complete") String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,description = "True when another page exists") boolean hasMore) {
        static TransferQueuePage from(Page<TransferSummary> page) {
            return new TransferQueuePage(page.items().stream().map(TransferQueueItem::from).toList(), null, page.hasMore());
        }
        static TransferQueuePage from(com.stockrescue.transfers.persistence.TransferRepository.CursorPage<TransferSummary> page) {
            return new TransferQueuePage(page.items().stream().map(TransferQueueItem::from).toList(), page.nextCursor(), page.hasMore());
        }
    }

    public record TransferCapabilitiesResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StoreAction> sourceActions,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StoreAction> destinationActions,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canReceivePhysicalSettlement,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canConfirmPhysicalReturn,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TransferQueryService.HistoricalSettlementAction> historicalSettlementActions) { }

    public record TransferSettlementPositionsResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TransferSettlementPositionVersion> sourcePositions,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TransferSettlementPositionVersion> destinationPositions) { }

    public record TransferSettlementPositionVersion(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID lineId,
            @Schema(types = {"string", "null"}, format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID positionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version) {
        private static TransferSettlementPositionVersion from(
                com.stockrescue.inventory.application.InventoryService.TransferSettlementPositionVersion value) {
            return new TransferSettlementPositionVersion(value.lineId(), value.positionId(), value.version());
        }
    }

    public record TransferQueueItem(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceStoreId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID destinationStoreId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TransferStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long lineCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigInteger outstandingQuantity) {
        static TransferQueueItem from(TransferSummary value) {
            return new TransferQueueItem(value.id(), value.sourceStoreId(), value.destinationStoreId(), value.status(),
                    value.version(), value.createdAt(), value.updatedAt(), value.lineCount(), value.outstandingQuantity());
        }
    }

    public record CommandResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID transferId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TransferStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID commandId,
            @Schema(types = {"string", "null"}, format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID stockMovementId) {
        static CommandResponse from(CommandOutcome value) {
            return new CommandResponse(value.transferId(), value.status(), value.version(), value.commandId(), value.stockMovementId());
        }
    }

    public record RecoveryResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String operation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CommandResponse outcome) {
        static RecoveryResponse from(Recovery value) {
            return new RecoveryResponse(value.operation(), value.requestId(), CommandResponse.from(value.outcome()));
        }
    }

    public record TransferResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceStoreId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID destinationStoreId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TransferStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long lineCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigInteger outstandingQuantity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TransferLineResponse> lines,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canCancel) {
        static TransferResponse from(TransferDetail value, boolean canCancel) {
            return new TransferResponse(value.transfer().id(), value.transfer().sourceStoreId(), value.transfer().destinationStoreId(),
                    value.transfer().status(), value.transfer().version(), value.transfer().createdAt(), value.transfer().updatedAt(),
                    value.transfer().lineCount(), value.transfer().outstandingQuantity(), value.lines().stream().map(TransferLineResponse::from).toList(),
                    canCancel);
        }
    }

    public record TransferLineResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int lineNumber,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourcePositionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID destinationLocationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID productId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID lotId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sku,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String lotCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long requestedQuantity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String unit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long expectedSourceVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean picked,
            @Schema(types = {"string", "null"}, format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID reservationAllocationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TransferQuantities quantities) {
        static TransferLineResponse from(TransferLine value) {
            return new TransferLineResponse(value.id(), value.lineNumber(), value.sourcePositionId(), value.destinationLocationId(),
                    value.productId(), value.lotId(), value.sku(), value.lotCode(), value.requestedQuantity(), "EA",
                    value.expectedSourceVersion(), value.picked(), value.reservationAllocationId(), value.quantities());
        }
    }
}
