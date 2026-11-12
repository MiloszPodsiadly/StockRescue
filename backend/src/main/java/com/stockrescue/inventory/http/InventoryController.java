package com.stockrescue.inventory.http;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.stockrescue.inventory.application.InventoryCommands.AllocationChange;
import com.stockrescue.inventory.application.InventoryCommands.PositionReference;
import com.stockrescue.inventory.application.InventoryCommands.ReceiveGoods;
import com.stockrescue.inventory.application.InventoryCommands.Result;
import com.stockrescue.inventory.application.InventoryCommands.StockChange;
import com.stockrescue.inventory.application.InventoryReconciliationService;
import com.stockrescue.inventory.application.InventoryService;
import com.stockrescue.inventory.domain.StockPosition;
import com.stockrescue.inventory.persistence.InventoryReconciliationRepository.Finding;
import com.stockrescue.inventory.persistence.InventoryReconciliationRepository.Run;
import com.stockrescue.inventory.persistence.InventoryRepository.Allocation;
import com.stockrescue.inventory.persistence.InventoryRepository.Movement;
import com.stockrescue.inventory.persistence.InventoryRepository.Operation;
import com.stockrescue.platform.http.ApiProblem;
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
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
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
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value="/api/v1/organizations/{organizationId}/stores/{storeId}/inventory",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="session")
@ApiResponses({
        @ApiResponse(responseCode="400",description="Invalid inventory command or page",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="401",description="Authentication required",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="403",description="Current store action or CSRF denied",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="404",description="Organization, store or inventory record unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="409",description="Stale position, insufficient stock, changed retry, duplicate receipt or frozen stock",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="422",description="Invalid request values or current lot evidence do not permit the requested operation",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="500",description="Request failed",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class))),
        @ApiResponse(responseCode="503",description="Inventory dependency temporarily unavailable",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
})
public class InventoryController {
    private final InventoryService inventory;
    private final InventoryReconciliationService reconciliation;
    private final Clock clock;

    public InventoryController(InventoryService inventory,InventoryReconciliationService reconciliation,Clock clock) {
        this.inventory=inventory;this.reconciliation=reconciliation;this.clock=clock;
    }

    @GetMapping("/positions")
    @io.swagger.v3.oas.annotations.Operation(operationId="listInventoryPositions",summary="Read a bounded store-scoped page of recorded inventory positions")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=StockPageResponse.class)))
    public StockPageResponse positions(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @RequestParam(required=false) UUID productId,@RequestParam(required=false) UUID lotId,
            @RequestParam(required=false) UUID locationId,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var page=inventory.positions(actor(actor),organizationId,storeId,productId,lotId,locationId,offset,limit);
        Instant observedAt=clock.instant();
        return new StockPageResponse(page.items().stream().map(position->StockSnapshotResponse.from(position,observedAt)).toList(),page.hasMore(),observedAt);
    }

    @GetMapping("/available-by-lot")
    @io.swagger.v3.oas.annotations.Operation(operationId="listInventoryAvailableByLot",summary="Read availability by lot and storage position; unknown quantities stay null")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=AvailableByLotPageResponse.class)))
    public AvailableByLotPageResponse availableByLot(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @RequestParam(required=false) UUID productId,@RequestParam(required=false) UUID lotId,
            @RequestParam(required=false) UUID locationId,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var page=inventory.positions(actor(actor),organizationId,storeId,productId,lotId,locationId,offset,limit);
        Instant observedAt=clock.instant();
        return new AvailableByLotPageResponse(page.items().stream().map(position->AvailableByLotResponse.from(position,observedAt)).toList(),page.hasMore(),observedAt);
    }

    @GetMapping("/positions/{positionId}")
    @io.swagger.v3.oas.annotations.Operation(operationId="getInventorySnapshot",summary="Read an authoritative versioned PostgreSQL stock position")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=StockSnapshotResponse.class)))
    public StockSnapshotResponse snapshot(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,@PathVariable UUID positionId) {
        return StockSnapshotResponse.from(inventory.snapshot(actor(actor),organizationId,storeId,positionId),clock.instant());
    }

    @GetMapping("/positions/{positionId}/movements")
    @io.swagger.v3.oas.annotations.Operation(operationId="listInventoryMovements",summary="Read bounded immutable movement history after a position version")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=MovementPageResponse.class)))
    public MovementPageResponse movements(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID positionId,@RequestParam(defaultValue="0") @Min(0) long afterVersion,
            @RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var page=inventory.history(actor(actor),organizationId,storeId,positionId,afterVersion,limit);
        return new MovementPageResponse(page.items().stream().map(MovementResponse::from).toList(),page.hasMore());
    }

    @GetMapping("/positions/{positionId}/allocations")
    @io.swagger.v3.oas.annotations.Operation(operationId="listInventoryAllocations",summary="Read current reservation and hold status for one position")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=AllocationPageResponse.class)))
    public AllocationPageResponse allocations(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID positionId,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        UUID actorId=actor(actor);
        var page=inventory.allocations(actorId,organizationId,storeId,positionId,offset,limit);
        return new AllocationPageResponse(page.items().stream().map(allocation->AllocationResponse.from(allocation,actorId)).toList(),page.hasMore());
    }

    @PostMapping(value="/receipts",consumes=MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.Operation(operationId="receiveInventoryGoods",summary="Commit physical receipt and retry result in PostgreSQL")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=InventoryCommandResponse.class)))
    public ResponseEntity<InventoryCommandResponse> receive(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @Valid @RequestBody ReceiptRequest request) {
        var result=inventory.receiveGoods(actor(actor),organizationId,storeId,new ReceiveGoods(request.requestId(),
                new PositionReference(request.locationId(),request.productId(),request.lotId()),request.quantity(),request.supplierKey(),
                request.documentType(),request.documentReference(),request.documentLineReference(),request.physicallyReceivedAt(),
                request.reason(),request.evidenceReference(),request.expectedVersion()));
        return ResponseEntity.ok(InventoryCommandResponse.from(result));
    }

    @PostMapping(value="/positions/{positionId}/reservations",consumes=MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.Operation(operationId="reserveInventoryStock",summary="Reserve available stock against an expected position version")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=InventoryCommandResponse.class)))
    public InventoryCommandResponse reserve(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID positionId,@Valid @RequestBody StockCommandRequest request) {
        return command(inventory.reserveStock(actor(actor),organizationId,storeId,request.stock(positionId)));
    }

    @PostMapping(value="/positions/{positionId}/reservations/{allocationId}/release",consumes=MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.Operation(operationId="releaseInventoryReservation",summary="Release an owned or manager-authorized reservation")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=InventoryCommandResponse.class)))
    public InventoryCommandResponse releaseReservation(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID positionId,@PathVariable UUID allocationId,@Valid @RequestBody StockCommandRequest request) {
        return command(inventory.releaseReservation(actor(actor),organizationId,storeId,new AllocationChange(request.stock(positionId),allocationId)));
    }

    @PostMapping(value="/positions/{positionId}/adjustments",consumes=MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.Operation(operationId="adjustInventoryStock",summary="Record a reasoned version-aware stock adjustment")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=InventoryCommandResponse.class)))
    public InventoryCommandResponse adjust(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID positionId,@Valid @RequestBody StockCommandRequest request) {
        return command(inventory.adjustStock(actor(actor),organizationId,storeId,request.stock(positionId)));
    }

    @PostMapping(value="/positions/{positionId}/write-offs",consumes=MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.Operation(operationId="writeOffInventoryStock",summary="Write off available or explicitly allocated blocked stock")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=InventoryCommandResponse.class)))
    public InventoryCommandResponse writeOff(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID positionId,@Valid @RequestBody WriteOffRequest request) {
        return command(inventory.writeOff(actor(actor),organizationId,storeId,
                new AllocationChange(request.stock().stock(positionId),request.allocationId())));
    }

    @PostMapping(value="/positions/{positionId}/quarantines",consumes=MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.Operation(operationId="quarantineInventoryStock",summary="Move available stock into a quarantine allocation")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=InventoryCommandResponse.class)))
    public InventoryCommandResponse quarantine(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID positionId,@Valid @RequestBody StockCommandRequest request) {
        return command(inventory.quarantine(actor(actor),organizationId,storeId,request.stock(positionId)));
    }

    @PostMapping(value="/positions/{positionId}/holds",consumes=MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.Operation(operationId="holdInventoryStock",summary="Move available stock into an other-blocked allocation")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=InventoryCommandResponse.class)))
    public InventoryCommandResponse hold(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID positionId,@Valid @RequestBody StockCommandRequest request) {
        return command(inventory.hold(actor(actor),organizationId,storeId,request.stock(positionId)));
    }

    @PostMapping(value="/positions/{positionId}/holds/{allocationId}/release",consumes=MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.Operation(operationId="releaseInventoryHold",summary="Release a blocked allocation after current eligibility review")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=InventoryCommandResponse.class)))
    public InventoryCommandResponse releaseHold(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID positionId,@PathVariable UUID allocationId,@Valid @RequestBody StockCommandRequest request) {
        return command(inventory.releaseHold(actor(actor),organizationId,storeId,new AllocationChange(request.stock(positionId),allocationId)));
    }

    @PostMapping(value="/positions/{positionId}/sales",consumes=MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.Operation(operationId="recordInventorySale",summary="Record a manual sale after server-side eligibility checks")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=InventoryCommandResponse.class)))
    public InventoryCommandResponse sale(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID positionId,@Valid @RequestBody StockCommandRequest request) {
        return command(inventory.recordSale(actor(actor),organizationId,storeId,request.stock(positionId)));
    }

    @GetMapping("/commands/{operation}/{requestId}")
    @io.swagger.v3.oas.annotations.Operation(operationId="getInventoryCommandOutcome",summary="Recover an actor-scoped committed result after an uncertain response")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=InventoryCommandResponse.class)))
    public InventoryCommandResponse commandOutcome(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable Operation operation,@PathVariable UUID requestId) {
        return command(inventory.commandOutcome(actor(actor),organizationId,storeId,operation,requestId));
    }

    @GetMapping("/reconciliations")
    @io.swagger.v3.oas.annotations.Operation(operationId="listInventoryReconciliations",summary="Read bounded immutable reconciliation history")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=ReconciliationPageResponse.class)))
    public ReconciliationPageResponse reconciliations(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,@RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var page=reconciliation.runs(actor(actor),organizationId,storeId,offset,limit);
        return new ReconciliationPageResponse(page.items().stream().map(ReconciliationResponse::from).toList(),page.hasMore());
    }

    @PostMapping("/reconciliations")
    @io.swagger.v3.oas.annotations.Operation(operationId="reconcileInventory",summary="Reconstruct store stock from immutable inventory evidence")
    @ApiResponse(responseCode="201",content=@Content(schema=@Schema(implementation=ReconciliationResponse.class)))
    public ResponseEntity<ReconciliationResponse> reconcile(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ReconciliationResponse.from(reconciliation.reconcile(actor(actor),organizationId,storeId)));
    }

    @GetMapping("/reconciliations/{runId}/findings")
    @io.swagger.v3.oas.annotations.Operation(operationId="listInventoryReconciliationFindings",summary="Read bounded findings for one authorized reconciliation run")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=FindingPageResponse.class)))
    public FindingPageResponse findings(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID runId,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset,
            @RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        var page=reconciliation.findings(actor(actor),organizationId,storeId,runId,offset,limit);
        return new FindingPageResponse(page.items().stream().map(FindingResponse::from).toList(),page.hasMore());
    }

    @PostMapping(value="/reconciliations/{runId}/positions/{positionId}/projection-repair",consumes=MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.Operation(operationId="repairInventoryProjection",summary="Rebuild a frozen position only from validated immutable ledger and allocation evidence")
    @ApiResponse(responseCode="200",content=@Content(schema=@Schema(implementation=InventoryCommandResponse.class)))
    public InventoryCommandResponse repairProjection(Authentication actor,@PathVariable UUID organizationId,@PathVariable UUID storeId,
            @PathVariable UUID runId,@PathVariable UUID positionId,@Valid @RequestBody ProjectionRepairRequest request) {
        var result=reconciliation.remediateProjection(actor(actor),organizationId,storeId,
                new InventoryReconciliationService.RepairRequest(request.requestId(),runId,positionId,request.expectedVersion(),
                        request.reason(),request.evidenceReference()));
        return InventoryCommandResponse.from(result);
    }

    private static UUID actor(Authentication actor) { return UUID.fromString(actor.getName()); }
    private static InventoryCommandResponse command(Result result) { return InventoryCommandResponse.from(result); }

    public record ReceiptRequest(@NotNull UUID requestId,@NotNull @Min(0) @Max(Long.MAX_VALUE-1) Long expectedVersion,
            @NotNull UUID locationId,@NotNull UUID productId,@NotNull UUID lotId,@NotNull @Positive Long quantity,
            @NotBlank @Size(max=200) String supplierKey,@NotBlank @Size(max=32) String documentType,
            @NotBlank @Size(max=200) String documentReference,@NotBlank @Size(max=64) String documentLineReference,
            @NotNull Instant physicallyReceivedAt,@NotBlank @Size(max=500) String reason,@NotBlank @Size(max=256) String evidenceReference) { }

    public record StockCommandRequest(@NotNull UUID requestId,@NotNull @Min(0) @Max(Long.MAX_VALUE-1) Long expectedVersion,
            @NotNull Long quantity,@NotBlank @Size(max=500) String reason,@NotBlank @Size(max=256) String evidenceReference) {
        StockChange stock(UUID positionId) { return new StockChange(requestId,positionId,expectedVersion,quantity,reason,evidenceReference); }
    }

    public record WriteOffRequest(@Valid @NotNull StockCommandRequest stock,UUID allocationId) { }
    public record ProjectionRepairRequest(@NotNull UUID requestId,@NotNull @Min(0) @Max(Long.MAX_VALUE-1) Long expectedVersion,
            @NotBlank @Size(max=500) String reason,@NotBlank @Size(max=256) String evidenceReference) { }
    public record StockPageResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<StockSnapshotResponse> items,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant observedAt) { }
    public record AvailableByLotPageResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<AvailableByLotResponse> items,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant observedAt) { }

    public record StockSnapshotResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID productId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID lotId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID locationId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String unit,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) StockPosition.Initialization initialization,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) StockPosition.Trust trust,
            @Schema(types={"integer","null"},format="int64",requiredMode=Schema.RequiredMode.REQUIRED) Long onHand,
            @Schema(types={"integer","null"},format="int64",requiredMode=Schema.RequiredMode.REQUIRED) Long reserved,
            @Schema(types={"integer","null"},format="int64",requiredMode=Schema.RequiredMode.REQUIRED) Long quarantined,
            @Schema(types={"integer","null"},format="int64",requiredMode=Schema.RequiredMode.REQUIRED) Long otherBlocked,
            @Schema(types={"integer","null"},format="int64",requiredMode=Schema.RequiredMode.REQUIRED) Long available,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long version,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant observedAt) {
        static StockSnapshotResponse from(StockPosition position,Instant observedAt) {
            boolean initialized=position.initialization()==StockPosition.Initialization.INITIALIZED;
            var buckets=position.buckets();
            return new StockSnapshotResponse(position.id(),position.productId(),position.lotId(),position.locationId(),"EA",
                    position.initialization(),position.trust(),initialized?buckets.onHand():null,
                    initialized?buckets.reserved():null,initialized?buckets.quarantined():null,initialized?buckets.otherBlocked():null,
                    initialized?buckets.available():null,position.version(),observedAt);
        }
    }

    public record AvailableByLotResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID positionId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID productId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID lotId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID locationId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String unit,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) StockPosition.Initialization initialization,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) StockPosition.Trust trust,
            @Schema(types={"integer","null"},format="int64",requiredMode=Schema.RequiredMode.REQUIRED) Long available,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long positionVersion,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant observedAt) {
        static AvailableByLotResponse from(StockPosition position,Instant observedAt) {
            boolean initialized=position.initialization()==StockPosition.Initialization.INITIALIZED;
            return new AvailableByLotResponse(position.id(),position.productId(),position.lotId(),position.locationId(),"EA",
                    position.initialization(),position.trust(),initialized?position.buckets().available():null,
                    position.version(),observedAt);
        }
    }

    public record MovementPageResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<MovementResponse> items,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore) { }
    public record MovementResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Operation operation,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long positionVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long deltaOnHand,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long deltaReserved,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long deltaQuarantined,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long deltaOtherBlocked,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String reason,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String evidenceReference,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant recordedAt) {
        static MovementResponse from(Movement movement) {
            var delta=movement.delta();
            return new MovementResponse(movement.id(),movement.operation(),movement.positionVersion(),delta.onHand(),
                    delta.reserved(),delta.quarantined(),delta.otherBlocked(),movement.reason(),movement.evidenceReference(),movement.recordedAt());
        }
    }

    public record AllocationPageResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<AllocationResponse> items,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore) { }
    public record AllocationResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID positionId,
            @Schema(allowableValues={"RESERVED","QUARANTINED","OTHER_BLOCKED"},requiredMode=Schema.RequiredMode.REQUIRED) String bucket,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long remaining,
            @Schema(allowableValues={"ACTIVE","RELEASED","WRITTEN_OFF","CONSUMED"},requiredMode=Schema.RequiredMode.REQUIRED) String state,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long version,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean ownedByCurrentUser) {
        static AllocationResponse from(Allocation allocation,UUID actor) {
            return new AllocationResponse(allocation.id(),allocation.positionId(),allocation.bucket(),allocation.remaining(),
                    allocation.state(),allocation.version(),allocation.ownerId().equals(actor));
        }
    }

    public record InventoryCommandResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID movementId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID positionId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long positionVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long onHand,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long reserved,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long quarantined,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) long otherBlocked,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long available,
            @Schema(types={"string","null"},format="uuid",requiredMode=Schema.RequiredMode.REQUIRED) UUID allocationId,
            @Schema(types={"string","null"},format="uuid",requiredMode=Schema.RequiredMode.REQUIRED) UUID receiptId) {
        static InventoryCommandResponse from(Result result) {
            var buckets=result.buckets();
            return new InventoryCommandResponse(result.movementId(),result.positionId(),result.positionVersion(),buckets.onHand(),
                    buckets.reserved(),buckets.quarantined(),buckets.otherBlocked(),buckets.available(),result.allocationId(),result.receiptId());
        }
    }

    public record ReconciliationPageResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<ReconciliationResponse> items,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore) { }
    public record ReconciliationResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID organizationId,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID storeId,
            @Schema(allowableValues={"CLEAN","WARN","CRITICAL"},requiredMode=Schema.RequiredMode.REQUIRED) String severity,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int checkedPositions,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant startedAt,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant completedAt) {
        static ReconciliationResponse from(Run run) {
            return new ReconciliationResponse(run.id(),run.organizationId(),run.storeId(),run.severity(),run.checkedPositions(),run.startedAt(),run.completedAt());
        }
    }
    public record FindingPageResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<FindingResponse> items,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean hasMore) { }
    public record FindingResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID positionId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long positionVersion,@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String severity,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String code,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String expected,
            @Schema(types={"string","null"},requiredMode=Schema.RequiredMode.REQUIRED) String observed) {
        static FindingResponse from(Finding finding) {
            return new FindingResponse(finding.positionId(),finding.positionVersion(),finding.severity(),finding.code(),finding.expected(),finding.observed());
        }
    }
}
