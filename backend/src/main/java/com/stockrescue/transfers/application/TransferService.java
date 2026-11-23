package com.stockrescue.transfers.application;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.inventory.application.InventoryService;
import com.stockrescue.inventory.application.InventoryService.TransferReservationLine;
import com.stockrescue.inventory.application.InventoryService.TransferReleaseLine;
import com.stockrescue.inventory.application.InventoryService.TransferDispatchLine;
import com.stockrescue.inventory.application.InventoryService.TransferReceiptLine;
import com.stockrescue.inventory.application.InventoryService.TransferReturnLine;
import com.stockrescue.inventory.application.InventoryService.TransferSettlementPositionRequest;
import com.stockrescue.inventory.application.InventoryService.TransferSettlementPositionVersion;
import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.inventory.domain.InventoryOperation;
import com.stockrescue.inventory.domain.StockPosition;
import com.stockrescue.inventory.persistence.InventoryRepository;
import com.stockrescue.inventory.persistence.InventoryRepository.PositionIdentity;
import com.stockrescue.platform.audit.AuditWriter;
import com.stockrescue.platform.correlation.CorrelationIds;
import com.stockrescue.stores.application.LaneService;
import com.stockrescue.stores.application.LaneService.TransferRoute;
import com.stockrescue.transfers.domain.TransferStatus;
import com.stockrescue.transfers.domain.TransferWriteConflict;
import com.stockrescue.transfers.persistence.TransferRepository;
import com.stockrescue.transfers.persistence.TransferRepository.NewTransferLine;
import com.stockrescue.transfers.persistence.TransferRepository.TransferCommandReceipt;
import com.stockrescue.transfers.persistence.TransferRepository.TransferDetail;
import com.stockrescue.transfers.persistence.TransferRepository.TransferIdentity;
import com.stockrescue.transfers.persistence.TransferRepository.PhysicalDocument;
import com.stockrescue.transfers.persistence.TransferRepository.PhysicalDocumentLine;
import com.stockrescue.transfers.persistence.TransferCommandIntentRepository;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Service
public class TransferService {
    public static final int MAX_LINES = 50;
    public static final Set<String> COMMAND_OPERATIONS = Set.of("CREATE", "APPROVE", "REJECT", "CANCEL", "START_PICK",
            "PICK", "DISPATCH", "RECEIVE", "OPEN_DISCREPANCY", "CONFIRM_LOSS", "REQUEST_RETURN", "CONFIRM_RETURN", "CLOSE");
    private static final String CREATE = "CREATE";
    private static final String APPROVE = "APPROVE";
    private static final String REJECT = "REJECT";
    private static final String CANCEL = "CANCEL";
    private static final String START_PICK = "START_PICK";
    private static final String PICK = "PICK";
    private static final String DISPATCH = "DISPATCH";
    private static final String RECEIVE = "RECEIVE";
    private static final String OPEN_DISCREPANCY = "OPEN_DISCREPANCY";
    private static final String CONFIRM_LOSS = "CONFIRM_LOSS";
    private static final String REQUEST_RETURN = "REQUEST_RETURN";
    private static final String CONFIRM_RETURN = "CONFIRM_RETURN";
    private static final String CLOSE = "CLOSE";

    public record CreateLine(UUID sourcePositionId, UUID destinationLocationId, long quantity,
            long expectedSourceVersion) { }
    public record CreateRequest(UUID requestId, UUID sourceStoreId, UUID destinationStoreId,
            List<CreateLine> lines, String reason, String evidenceReference) { }
    public record DecisionRequest(UUID requestId, long expectedVersion, String reason, String evidenceReference) { }
    public record CancelRequest(UUID requestId, long expectedVersion, String reason, String evidenceReference,
            Boolean putBackConfirmed, String putBackDocumentReference) { }
    public record PickRequest(UUID requestId, long expectedVersion, UUID lineId, String documentReference,
            long quantity, String observedSku, String observedLotCode, String reason, String evidenceReference) { }
    public record DispatchLine(UUID lineId, long quantity, String observedSku, String observedLotCode) { }
    public record DispatchRequest(UUID requestId, long expectedVersion, String documentReference,
            List<DispatchLine> lines, String reason, String evidenceReference) { }
    public record ReceiveLine(UUID lineId, long quantity, long expectedDestinationVersion, String documentLineReference,
            String observedSku, String observedLotCode) { }
    public record ReceiveRequest(UUID requestId, long expectedVersion, String documentReference, Instant physicallyReceivedAt,
            List<ReceiveLine> lines, String reason, String evidenceReference) { }
    public record DamageLine(UUID lineId, long quantity, String documentLineReference) { }
    public record DamageRequest(UUID requestId, long expectedVersion, String documentReference, String reasonCode,
            List<DamageLine> lines, String reason, String evidenceReference) { }
    public record DiscrepancyRequest(UUID requestId, long expectedVersion, UUID lineId, String expectedState,
            String observedState, String reason, String evidenceReference) { }
    public record ReturnRequestLine(UUID lineId) { }
    public record RequestReturn(UUID requestId, long expectedVersion, String requestReference,
            List<ReturnRequestLine> lines, String reason, String evidenceReference) { }
    public record ConfirmReturnLine(UUID lineId, long quantity, long expectedSourceVersion, String documentLineReference,
            String observedSku, String observedLotCode) { }
    public record ConfirmReturnRequest(UUID requestId, long expectedVersion, String requestReference, String documentReference,
            List<ConfirmReturnLine> lines, String reason, String evidenceReference) { }
    public record CommandOutcome(UUID transferId, TransferStatus status, long version, UUID commandId,
            UUID stockMovementId) { }
    public record Recovery(String operation, UUID requestId, CommandOutcome outcome) { }
    public record TransferSettlementPositions(List<TransferSettlementPositionVersion> sourcePositions,
            List<TransferSettlementPositionVersion> destinationPositions) {
        public TransferSettlementPositions {
            sourcePositions = List.copyOf(sourcePositions);
            destinationPositions = List.copyOf(destinationPositions);
        }
    }

    private final AccessPolicy access;
    private final TransferQueryService queries;
    private final TransferCommandFingerprintService fingerprints;
    private final InventoryRepository inventory;
    private final TransferRepository transfers;
    private final TransferCommandIntentRepository commandIntents;
    private final InventoryService stock;
    private final LaneService lanes;
    private final AuditWriter audit;
    private final ObjectMapper json;

    public TransferService(AccessPolicy access, TransferQueryService queries, TransferCommandFingerprintService fingerprints,
            InventoryRepository inventory, TransferRepository transfers, TransferCommandIntentRepository commandIntents,
            InventoryService stock, LaneService lanes, AuditWriter audit, ObjectMapper json) {
        this.access = access;
        this.queries = queries;
        this.fingerprints = fingerprints;
        this.inventory = inventory;
        this.transfers = transfers;
        this.commandIntents = commandIntents;
        this.stock = stock;
        this.lanes = lanes;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public CommandOutcome create(UUID actor, UUID organizationId, CreateRequest request) {
        var normalized = TransferRequestNormalizer.normalize(request);
        var member = access.requireTransferStore(actor, organizationId, normalized.sourceStoreId(), StoreAction.REQUEST_TRANSFER, true);
        byte[] digest = commandFingerprint(organizationId, CREATE, null, normalized);
        transfers.lockCommand(organizationId, actor, CREATE, normalized.requestId());
        var replay = replay(actor, organizationId, CREATE, null, normalized.requestId(), digest);
        if (replay != null) return replay;

        lanes.lockTransferStoresForRequest(organizationId, normalized.sourceStoreId(), normalized.destinationStoreId());
        lockStoreAdvisories(organizationId, normalized.sourceStoreId(), normalized.destinationStoreId());
        var observed = normalized.lines().stream().map(line -> inventory.position(organizationId,
                normalized.sourceStoreId(), line.sourcePositionId(), false).orElseThrow(OrganizationUnavailableException::new)).toList();
        inventory.lockActiveContexts(organizationId, normalized.sourceStoreId(), observed.stream()
                .map(position -> new PositionIdentity(position.locationId(), position.productId(), position.lotId())).toList());
        for (var locationId : normalized.lines().stream()
                .map(TransferRequestNormalizer.NormalizedLine::destinationLocationId).distinct().sorted().toList()) {
            if (!transfers.lockDestinationContext(organizationId, normalized.destinationStoreId(), locationId))
                throw new OrganizationUnavailableException();
        }
        var locked = inventory.lockPositions(organizationId, normalized.sourceStoreId(),
                normalized.lines().stream().map(TransferRequestNormalizer.NormalizedLine::sourcePositionId).toList());
        var byId = locked.stream().collect(java.util.stream.Collectors.toMap(StockPosition::id, position -> position));
        for (var line : normalized.lines()) {
            var position = byId.get(line.sourcePositionId());
            if (position == null) throw new OrganizationUnavailableException();
            if (position.version() != line.expectedSourceVersion()) throw new InventoryFailure(InventoryFailure.Kind.CONFLICT);
        }

        UUID transferId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var lines = normalized.lines().stream().map(line -> new NewTransferLine(UUID.randomUUID(), line.lineNumber(),
                line.sourcePositionId(), line.destinationLocationId(), line.quantity(), line.expectedSourceVersion())).toList();
        var created = transfers.create(organizationId, transferId, normalized.sourceStoreId(), normalized.destinationStoreId(),
                actor, member.membershipId(), lines);
        var outcome = new CommandOutcome(transferId, TransferStatus.REQUESTED, created.transfer().version(), commandId, null);
        recordCommand(organizationId, transferId, actor, member.membershipId(), CREATE, normalized.requestId(), digest,
                outcome, null, null, null, commandId);
        transfers.recordInitialTransition(organizationId, transferId, commandId, actor, member.membershipId(),
                normalized.reason(), normalized.evidenceReference());
        audit.securityChange(actor, organizationId, "transfer", transferId, "TRANSFER_REQUESTED",
                Map.of("outcome", "SUCCEEDED", "after", created, "commandId", commandId), normalized.reason());
        return outcome;
    }

    @Transactional
    public CommandOutcome approve(UUID actor, UUID organizationId, UUID transferId, DecisionRequest request) {
        var normalized = TransferRequestNormalizer.normalize(request);
        var identity = identity(organizationId, transferId);
        var member = access.requireTransferStore(actor, organizationId, identity.sourceStoreId(), StoreAction.APPROVE_TRANSFER, true);
        access.requireTransferStore(actor, organizationId, identity.sourceStoreId(), StoreAction.RESERVE_STOCK, true);
        byte[] digest = commandFingerprint(organizationId, APPROVE, transferId, normalized);
        transfers.lockCommand(organizationId, actor, APPROVE, normalized.requestId());
        var replay = replay(actor, organizationId, APPROVE, transferId, normalized.requestId(), digest);
        if (replay != null) return replay;
        if (identity.status() != TransferStatus.REQUESTED || identity.version() != normalized.expectedVersion())
            throw new TransferWriteConflict();

        inventory.lockRequest(organizationId, actor, InventoryOperation.TRANSFER_RESERVE, normalized.requestId());
        lockStores(organizationId, identity.sourceStoreId(), identity.destinationStoreId());
        var detail = transfers.detail(organizationId, transferId).orElseThrow(OrganizationUnavailableException::new);
        if (detail.lines().isEmpty() || detail.lines().size() != detail.transfer().lineCount())
            throw new TransferWriteConflict();

        var observed = detail.lines().stream().map(line -> inventory.position(organizationId,
                identity.sourceStoreId(), line.sourcePositionId(), false)
                .orElseThrow(OrganizationUnavailableException::new)).toList();
        var observedById = observed.stream().collect(java.util.stream.Collectors.toMap(StockPosition::id, position -> position));
        TransferRoute route = null;
        for (var line : detail.lines()) {
            var position = observedById.get(line.sourcePositionId());
            if (position == null) throw new OrganizationUnavailableException();
            var currentRoute = lanes.requireTransferRoute(organizationId, identity.sourceStoreId(),
                    identity.destinationStoreId(), position.locationId(), line.destinationLocationId());
            if (route != null && !route.equals(currentRoute)) throw new TransferWriteConflict();
            route = currentRoute;
        }
        inventory.lockActiveContexts(organizationId, identity.sourceStoreId(), observed.stream()
                .map(position -> new PositionIdentity(position.locationId(), position.productId(), position.lotId())).toList());
        for (var line : detail.lines()) {
            var position = observedById.get(line.sourcePositionId());
            lanes.lockTransferLocations(organizationId, identity.sourceStoreId(), identity.destinationStoreId(),
                    position.locationId(), line.destinationLocationId(), route);
        }

        var current = transfers.lockTransferForCommand(organizationId, transferId);
        expectVersionAndStatus(current.status(), current.version(), normalized.expectedVersion(), TransferStatus.REQUESTED);
        var reservationLines = detail.lines().stream().map(line -> new TransferReservationLine(line.id(),
                line.sourcePositionId(), line.expectedSourceVersion(), line.requestedQuantity())).toList();
        var reserved = stock.reserveForTransfer(actor, organizationId, current.sourceStoreId(), normalized.requestId(),
                transferId, normalized.reason(), normalized.evidenceReference(), reservationLines);
        if (reserved.lines().size() != detail.lines().size()) throw new TransferWriteConflict();
        var allocationByLine = new java.util.HashMap<UUID, UUID>();
        for (var result : reserved.lines()) {
            var line = detail.lines().stream().filter(candidate -> candidate.id().equals(result.lineId()))
                    .findFirst().orElseThrow(TransferWriteConflict::new);
            if (!line.sourcePositionId().equals(result.positionId())
                    || line.requestedQuantity() != result.quantity()
                    || allocationByLine.putIfAbsent(result.lineId(), result.allocationId()) != null)
                throw new TransferWriteConflict();
        }
        if (allocationByLine.size() != detail.lines().size()) throw new TransferWriteConflict();

        long nextVersion = nextVersion(current.version());
        var commandId = UUID.randomUUID();
        var outcome = new CommandOutcome(transferId, TransferStatus.APPROVED, nextVersion, commandId, reserved.movementId());
        recordCommand(organizationId, transferId, actor, member.membershipId(), APPROVE, normalized.requestId(), digest,
                outcome, current.sourceStoreId(), reserved.commandId(), InventoryOperation.TRANSFER_RESERVE.name(), commandId);
        transfers.linkReservations(organizationId, transferId, detail.lines(), allocationByLine);
        transfers.advanceStatus(organizationId, transferId, current.version(), TransferStatus.APPROVED, commandId,
                actor, member.membershipId(), normalized.reason(), normalized.evidenceReference(),
                route.laneId(), route.revisionId());
        audit.securityChange(actor, organizationId, "transfer", transferId, "TRANSFER_APPROVED",
                Map.of("outcome", "SUCCEEDED", "before", current.status(), "after", TransferStatus.APPROVED,
                        "version", nextVersion, "commandId", commandId, "laneId", route.laneId(),
                        "laneRevisionId", route.revisionId(), "reservationMovementId", reserved.movementId()),
                normalized.reason());
        return outcome;
    }

    @Transactional
    public CommandOutcome reject(UUID actor, UUID organizationId, UUID transferId, DecisionRequest request) {
        var normalized = TransferRequestNormalizer.normalize(request);
        var identity = identity(organizationId, transferId);
        var member = access.requireTransferStore(actor, organizationId, identity.sourceStoreId(), StoreAction.APPROVE_TRANSFER, true);
        byte[] digest = commandFingerprint(organizationId, REJECT, transferId, normalized);
        transfers.lockCommand(organizationId, actor, REJECT, normalized.requestId());
        var replay = replay(actor, organizationId, REJECT, transferId, normalized.requestId(), digest);
        if (replay != null) return replay;
        lockStores(organizationId, identity.sourceStoreId(), identity.destinationStoreId());
        var current = transfers.lockTransferForCommand(organizationId, transferId);
        expectVersionAndStatus(current.status(), current.version(), normalized.expectedVersion(), TransferStatus.REQUESTED);
        long nextVersion = nextVersion(current.version());
        var commandId = UUID.randomUUID();
        var outcome = new CommandOutcome(transferId, TransferStatus.REJECTED, nextVersion, commandId, null);
        recordCommand(organizationId, transferId, actor, member.membershipId(), REJECT, normalized.requestId(), digest,
                outcome, null, null, null, commandId);
        transfers.advanceStatus(organizationId, transferId, current.version(), TransferStatus.REJECTED, commandId,
                actor, member.membershipId(), normalized.reason(), normalized.evidenceReference(), null, null);
        audit.securityChange(actor, organizationId, "transfer", transferId, "TRANSFER_REJECTED",
                Map.of("outcome", "SUCCEEDED", "before", current.status(), "after", TransferStatus.REJECTED,
                        "version", nextVersion, "commandId", commandId, "evidenceReference", normalized.evidenceReference()),
                normalized.reason());
        return outcome;
    }

    @Transactional
    public CommandOutcome cancel(UUID actor, UUID organizationId, UUID transferId, CancelRequest request) {
        var normalized = TransferRequestNormalizer.normalize(request);
        var identity = identity(organizationId, transferId);
        boolean originalOwnerRetry = actor.equals(identity.createdByUserId())
                && transfers.command(organizationId, actor, CANCEL, normalized.requestId()).isPresent();
        var action = cancellationAction(actor, identity.createdByUserId(), identity.status(), originalOwnerRetry);
        var member = access.requireTransferStore(actor, organizationId, identity.sourceStoreId(), action, true);
        byte[] digest = commandFingerprint(organizationId, CANCEL, transferId, normalized);
        transfers.lockCommand(organizationId, actor, CANCEL, normalized.requestId());
        var replay = replay(actor, organizationId, CANCEL, transferId, normalized.requestId(), digest);
        if (replay != null) return replay;
        inventory.lockRequest(organizationId, actor, InventoryOperation.TRANSFER_RELEASE, normalized.requestId());
        lockStores(organizationId, identity.sourceStoreId(), identity.destinationStoreId());
        var current = transfers.lockTransferForCommand(organizationId, transferId);
        var currentAction = cancellationAction(actor, current.createdByUserId(), current.status(), false);
        member = access.requireTransferStore(actor, organizationId, current.sourceStoreId(), currentAction, true);
        if (current.version() != normalized.expectedVersion()) throw new TransferWriteConflict();
        if (current.status() != TransferStatus.REQUESTED && current.status() != TransferStatus.APPROVED
                && current.status() != TransferStatus.PICKING && current.status() != TransferStatus.PICKED)
            throw new TransferWriteConflict();

        UUID stockCommandId = null;
        UUID stockMovementId = null;
        String stockOperation = null;
        UUID stockStoreId = null;
        var detail = current.status() == TransferStatus.REQUESTED ? null
                : transfers.detail(organizationId, transferId).orElseThrow(OrganizationUnavailableException::new);
        var pickEvidenceByLine = new java.util.HashMap<UUID, TransferRepository.PickEvidence>();
        if (detail != null) {
            for (var line : detail.lines()) {
                var pick = transfers.pickEvidence(organizationId, transferId, line.id());
                if (line.picked() != pick.isPresent()) throw new TransferWriteConflict();
                pick.ifPresent(evidence -> pickEvidenceByLine.put(line.id(), evidence));
            }
        }
        var pickedLines = detail == null ? List.<TransferRepository.TransferLine>of()
                : detail.lines().stream().filter(line -> pickEvidenceByLine.containsKey(line.id())).toList();
        if (!pickedLines.isEmpty()) {
            if (!normalized.putBackConfirmed() || normalized.putBackDocumentReference() == null)
                throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
            transfers.lockDocumentIdentity(organizationId, current.sourceStoreId(), "PUT_BACK",
                    normalized.putBackDocumentReference());
            if (transfers.physicalDocumentExists(organizationId, current.sourceStoreId(), "PUT_BACK",
                    normalized.putBackDocumentReference()))
                throw new InventoryFailure(InventoryFailure.Kind.DOCUMENT_ALREADY_RECEIVED);
        } else if (normalized.putBackConfirmed() || normalized.putBackDocumentReference() != null) {
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        }

        if (detail != null) {
            var releaseLines = detail.lines().stream().map(line -> {
                if (line.reservationAllocationId() == null || line.quantities().dispatched() != 0)
                    throw new TransferWriteConflict();
                return new TransferReleaseLine(line.id(), line.sourcePositionId(), line.reservationAllocationId(),
                        line.requestedQuantity());
            }).toList();
            var released = stock.releaseForTransfer(actor, organizationId, current.sourceStoreId(),
                    normalized.requestId(), transferId, normalized.reason(), normalized.evidenceReference(), releaseLines);
            stockCommandId = released.commandId();
            stockMovementId = released.movementId();
            stockOperation = "TRANSFER_RELEASE";
            stockStoreId = current.sourceStoreId();
        }
        long nextVersion = nextVersion(current.version());
        var commandId = UUID.randomUUID();
        var outcome = new CommandOutcome(transferId, TransferStatus.CANCELLED, nextVersion, commandId, stockMovementId);
        recordCommand(organizationId, transferId, actor, member.membershipId(), CANCEL, normalized.requestId(), digest,
                outcome, stockStoreId, stockCommandId, stockOperation, commandId);
        if (!pickedLines.isEmpty()) {
            UUID documentId = UUID.randomUUID();
            transfers.recordPhysicalDocument(new PhysicalDocument(documentId, organizationId, transferId,
                    current.sourceStoreId(), current.destinationStoreId(), current.sourceStoreId(), commandId,
                    "SOURCE", "PUT_BACK", normalized.putBackDocumentReference(), normalized.reason(),
                    normalized.evidenceReference(), actor, member.membershipId()));
            for (var line : pickedLines) {
                var pick = pickEvidenceByLine.get(line.id());
                transfers.recordPhysicalDocumentLine(new PhysicalDocumentLine(organizationId, transferId, documentId,
                        line.id(), "PUT_BACK", line.lineNumber(), line.id().toString(), pick.quantity(), "AVAILABLE",
                        pick.observedSku(), pick.observedLotCode(), pick.sourcePositionVersion()));
            }
        }
        transfers.advanceStatus(organizationId, transferId, current.version(), TransferStatus.CANCELLED, commandId,
                actor, member.membershipId(), normalized.reason(), normalized.evidenceReference(), null, null);
        audit.securityChange(actor, organizationId, "transfer", transferId, "TRANSFER_CANCELLED",
                Map.of("outcome", "SUCCEEDED", "before", current.status(), "after", TransferStatus.CANCELLED,
                        "version", nextVersion, "commandId", commandId, "stockMovementId",
                        stockMovementId == null ? "NONE" : stockMovementId, "evidenceReference", normalized.evidenceReference(),
                        "putBackDocumentReference", normalized.putBackDocumentReference() == null
                                ? "NONE" : normalized.putBackDocumentReference()),
                normalized.reason());
        return outcome;
    }

    @Transactional
    public CommandOutcome startPick(UUID actor, UUID organizationId, UUID transferId, DecisionRequest request) {
        var normalized = TransferRequestNormalizer.normalize(request);
        var identity = identity(organizationId, transferId);
        var member = access.requireTransferStore(actor, organizationId, identity.sourceStoreId(), StoreAction.PICK_TRANSFER, true);
        byte[] digest = commandFingerprint(organizationId, START_PICK, transferId, normalized);
        transfers.lockCommand(organizationId, actor, START_PICK, normalized.requestId());
        var replay = replay(actor, organizationId, START_PICK, transferId, normalized.requestId(), digest);
        if (replay != null) return replay;

        lockStores(organizationId, identity.sourceStoreId(), identity.destinationStoreId());
        var detail = transfers.detail(organizationId, transferId).orElseThrow(OrganizationUnavailableException::new);
        var route = requireCurrentTransferRoute(organizationId, identity.sourceStoreId(), identity.destinationStoreId(), detail.lines());
        var current = transfers.lockTransferForCommand(organizationId, transferId);
        expectVersionAndStatus(current.status(), current.version(), normalized.expectedVersion(), TransferStatus.APPROVED);
        requireApprovedRoute(current.approvedLaneId(), current.approvedLaneRevisionId(), route);
        for (var line : detail.lines()) {
            if (line.reservationAllocationId() == null) throw new TransferWriteConflict();
            stock.validateTransferPick(actor, organizationId, current.sourceStoreId(), transferId, line.id(),
                    line.sourcePositionId(), line.reservationAllocationId(), reservedPositionVersion(line), line.requestedQuantity());
        }

        long nextVersion = nextVersion(current.version());
        var commandId = UUID.randomUUID();
        var outcome = new CommandOutcome(transferId, TransferStatus.PICKING, nextVersion, commandId, null);
        recordCommand(organizationId, transferId, actor, member.membershipId(), START_PICK, normalized.requestId(), digest,
                outcome, null, null, null, commandId);
        transfers.advanceStatus(organizationId, transferId, current.version(), TransferStatus.PICKING, commandId,
                actor, member.membershipId(), normalized.reason(), normalized.evidenceReference(), null, null);
        audit.securityChange(actor, organizationId, "transfer", transferId, "TRANSFER_PICK_STARTED",
                Map.of("outcome", "SUCCEEDED", "before", current.status(), "after", TransferStatus.PICKING,
                        "version", nextVersion, "commandId", commandId, "laneId", route.laneId(),
                        "laneRevisionId", route.revisionId()), normalized.reason());
        return outcome;
    }

    @Transactional
    public CommandOutcome recordPick(UUID actor, UUID organizationId, UUID transferId, PickRequest request) {
        var normalized = TransferRequestNormalizer.normalize(request);
        var identity = identity(organizationId, transferId);
        var member = access.requireTransferStore(actor, organizationId, identity.sourceStoreId(), StoreAction.PICK_TRANSFER, true);
        byte[] digest = commandFingerprint(organizationId, PICK, transferId, normalized);
        transfers.lockCommand(organizationId, actor, PICK, normalized.requestId());
        var replay = replay(actor, organizationId, PICK, transferId, normalized.requestId(), digest);
        if (replay != null) return replay;

        lockStores(organizationId, identity.sourceStoreId(), identity.destinationStoreId());
        var detail = transfers.detail(organizationId, transferId).orElseThrow(OrganizationUnavailableException::new);
        var line = detail.lines().stream().filter(item -> item.id().equals(normalized.lineId()))
                .findFirst().orElseThrow(OrganizationUnavailableException::new);
        if (normalized.quantity() != line.requestedQuantity()
                || !normalized.observedSku().equals(line.sku())
                || !normalized.observedLotCode().equals(line.lotCode())
                || line.reservationAllocationId() == null
                || transfers.pickEvidence(organizationId, transferId, line.id()).isPresent())
            throw new TransferWriteConflict();
        var route = requireCurrentTransferRoute(organizationId, identity.sourceStoreId(), identity.destinationStoreId(),
                List.of(line));
        var current = transfers.lockTransferForCommand(organizationId, transferId);
        expectVersionAndStatus(current.status(), current.version(), normalized.expectedVersion(), TransferStatus.PICKING);
        requireApprovedRoute(current.approvedLaneId(), current.approvedLaneRevisionId(), route);
        long sourcePositionVersion = stock.validateTransferPick(actor, organizationId, current.sourceStoreId(), transferId,
                line.id(), line.sourcePositionId(), line.reservationAllocationId(), reservedPositionVersion(line), line.requestedQuantity());

        long pickedBefore = transfers.pickedLineCount(organizationId, transferId);
        if (pickedBefore >= detail.lines().size()) throw new TransferWriteConflict();
        var nextStatus = pickedBefore + 1 == detail.lines().size() ? TransferStatus.PICKED : TransferStatus.PICKING;
        long nextVersion = nextVersion(current.version());
        var commandId = UUID.randomUUID();
        var outcome = new CommandOutcome(transferId, nextStatus, nextVersion, commandId, null);
        recordCommand(organizationId, transferId, actor, member.membershipId(), PICK, normalized.requestId(), digest,
                outcome, null, null, null, commandId);
        UUID documentId = UUID.randomUUID();
        transfers.recordPhysicalDocument(new PhysicalDocument(documentId, organizationId, transferId,
                current.sourceStoreId(), current.destinationStoreId(), current.sourceStoreId(), commandId,
                "SOURCE", "PICK", normalized.documentReference(), normalized.reason(), normalized.evidenceReference(),
                actor, member.membershipId()));
        transfers.recordPhysicalDocumentLine(new PhysicalDocumentLine(organizationId, transferId, documentId, line.id(),
                "PICK", 1, line.id().toString(), line.requestedQuantity(), "PICKED", normalized.observedSku(),
                normalized.observedLotCode(), sourcePositionVersion));
        transfers.advanceStatus(organizationId, transferId, current.version(), nextStatus, commandId,
                actor, member.membershipId(), normalized.reason(), normalized.evidenceReference(), null, null);
        audit.securityChange(actor, organizationId, "transfer", transferId, "TRANSFER_LINE_PICKED",
                Map.ofEntries(Map.entry("outcome", "SUCCEEDED"), Map.entry("status", nextStatus),
                        Map.entry("version", nextVersion), Map.entry("commandId", commandId),
                        Map.entry("lineId", line.id()), Map.entry("sourcePositionId", line.sourcePositionId()),
                        Map.entry("quantity", line.requestedQuantity()), Map.entry("sourcePositionVersion", sourcePositionVersion),
                        Map.entry("documentReference", normalized.documentReference()),
                        Map.entry("observedSku", normalized.observedSku()),
                        Map.entry("observedLotCode", normalized.observedLotCode())), normalized.reason());
        return outcome;
    }

    @Transactional
    public CommandOutcome dispatch(UUID actor, UUID organizationId, UUID transferId, DispatchRequest request) {
        var normalized = TransferRequestNormalizer.normalize(request);
        var identity = identity(organizationId, transferId);
        var member = access.requireTransferStore(actor, organizationId, identity.sourceStoreId(), StoreAction.DISPATCH_TRANSFER, true);
        byte[] digest = commandFingerprint(organizationId, DISPATCH, transferId, normalized);
        transfers.lockCommand(organizationId, actor, DISPATCH, normalized.requestId());
        var replay = replay(actor, organizationId, DISPATCH, transferId, normalized.requestId(), digest);
        if (replay != null) return replay;

        lockStores(organizationId, identity.sourceStoreId(), identity.destinationStoreId());
        var detail = transfers.detail(organizationId, transferId).orElseThrow(OrganizationUnavailableException::new);
        if (detail.lines().size() != normalized.lines().size()) throw new TransferWriteConflict();
        var requestByLine = normalized.lines().stream().collect(java.util.stream.Collectors.toMap(DispatchLine::lineId, item -> item));
        var stockLines = new ArrayList<TransferDispatchLine>(detail.lines().size());
        for (var line : detail.lines()) {
            var submitted = requestByLine.get(line.id());
            var pick = transfers.pickEvidence(organizationId, transferId, line.id()).orElseThrow(TransferWriteConflict::new);
            if (submitted == null || submitted.quantity() != line.requestedQuantity()
                    || pick.quantity() != line.requestedQuantity()
                    || !submitted.observedSku().equals(line.sku()) || !submitted.observedLotCode().equals(line.lotCode())
                    || !submitted.observedSku().equals(pick.observedSku())
                    || !submitted.observedLotCode().equals(pick.observedLotCode())
                    || line.reservationAllocationId() == null || line.quantities().dispatched() != 0)
                throw new TransferWriteConflict();
            stockLines.add(new TransferDispatchLine(line.id(), line.sourcePositionId(), line.reservationAllocationId(),
                    pick.sourcePositionVersion(), line.requestedQuantity()));
        }
        var route = requireCurrentTransferRoute(organizationId, identity.sourceStoreId(), identity.destinationStoreId(), detail.lines());
        var current = transfers.lockTransferForCommand(organizationId, transferId);
        expectVersionAndStatus(current.status(), current.version(), normalized.expectedVersion(), TransferStatus.PICKED);
        requireApprovedRoute(current.approvedLaneId(), current.approvedLaneRevisionId(), route);
        var posted = stock.dispatchForTransfer(actor, organizationId, current.sourceStoreId(), normalized.requestId(),
                transferId, normalized.reason(), normalized.evidenceReference(), stockLines);
        if (posted.lines().size() != detail.lines().size()) throw new TransferWriteConflict();

        long nextVersion = nextVersion(current.version());
        var commandId = UUID.randomUUID();
        var outcome = new CommandOutcome(transferId, TransferStatus.IN_TRANSIT, nextVersion, commandId, posted.movementId());
        recordCommand(organizationId, transferId, actor, member.membershipId(), DISPATCH, normalized.requestId(), digest,
                outcome, current.sourceStoreId(), posted.commandId(), InventoryOperation.TRANSFER_OUT.name(), commandId);
        UUID documentId = UUID.randomUUID();
        transfers.recordPhysicalDocument(new PhysicalDocument(documentId, organizationId, transferId,
                current.sourceStoreId(), current.destinationStoreId(), current.sourceStoreId(), commandId,
                "SOURCE", "DISPATCH", normalized.documentReference(), normalized.reason(), normalized.evidenceReference(),
                actor, member.membershipId()));
        for (int index = 0; index < detail.lines().size(); index++) {
            var line = detail.lines().get(index);
            var submitted = requestByLine.get(line.id());
            transfers.recordPhysicalDocumentLine(new PhysicalDocumentLine(organizationId, transferId, documentId,
                    line.id(), "DISPATCH", line.lineNumber(), line.id().toString(), line.requestedQuantity(),
                    "COUNTED", submitted.observedSku(), submitted.observedLotCode(), null));
            var postedLine = posted.lines().stream().filter(item -> item.lineId().equals(line.id()))
                    .findFirst().orElseThrow(TransferWriteConflict::new);
            transfers.appendCustodyMovement(new TransferRepository.CustodyAppend(UUID.randomUUID(), organizationId,
                    transferId, line.id(), line.version(), com.stockrescue.transfers.domain.TransferMovementType.DISPATCH,
                    commandId, actor, member.membershipId(), line.requestedQuantity(), current.sourceStoreId(),
                    posted.movementId(), line.sourcePositionId(), normalized.reason(), normalized.evidenceReference()));
            if (!postedLine.positionId().equals(line.sourcePositionId()) || postedLine.quantity() != line.requestedQuantity())
                throw new TransferWriteConflict();
        }
        transfers.advanceStatus(organizationId, transferId, current.version(), TransferStatus.IN_TRANSIT, commandId,
                actor, member.membershipId(), normalized.reason(), normalized.evidenceReference(), null, null);
        audit.securityChange(actor, organizationId, "transfer", transferId, "TRANSFER_DISPATCHED",
                Map.of("outcome", "SUCCEEDED", "before", current.status(), "after", TransferStatus.IN_TRANSIT,
                        "version", nextVersion, "commandId", commandId, "stockMovementId", posted.movementId(),
                        "documentReference", normalized.documentReference(), "lines", detail.lines()), normalized.reason());
        return outcome;
    }

    @Transactional
    public CommandOutcome receive(UUID actor, UUID organizationId, UUID transferId, ReceiveRequest request) {
        var normalized=TransferRequestNormalizer.normalize(request);
        var identity=identity(organizationId,transferId);
        var member=access.requireTransferSettlementStore(actor,organizationId,identity.destinationStoreId(),StoreAction.RECEIVE_STOCK);
        byte[] digest=commandFingerprint(organizationId,RECEIVE,transferId,normalized);
        transfers.lockCommand(organizationId,actor,RECEIVE,normalized.requestId());
        var replay=replay(actor,organizationId,RECEIVE,transferId,normalized.requestId(),digest);
        if(replay!=null) return replay;

        lockStores(organizationId,identity.sourceStoreId(),identity.destinationStoreId());
        transfers.lockDocumentIdentity(organizationId,identity.destinationStoreId(),"RECEIPT",normalized.documentReference());
        if(transfers.physicalDocumentExists(organizationId,identity.destinationStoreId(),"RECEIPT",normalized.documentReference()))
            throw new InventoryFailure(InventoryFailure.Kind.DOCUMENT_ALREADY_RECEIVED);
        var current=transfers.lockTransferForCommand(organizationId,transferId);
        requireSettleable(current,normalized.expectedVersion());
        var detail=transfers.detail(organizationId,transferId).orElseThrow(OrganizationUnavailableException::new);
        var byId=detail.lines().stream().collect(java.util.stream.Collectors.toMap(TransferRepository.TransferLine::id,line -> line));
        var stockLines=new ArrayList<TransferReceiptLine>(normalized.lines().size());
        var quantityByLine=new java.util.HashMap<UUID,Long>();
        var returnRequestsByLine=new java.util.HashMap<UUID,TransferRepository.PendingReturn>();
        for(var requested:normalized.lines()) {
            var line=byId.get(requested.lineId());
            if(line==null || requested.quantity()>line.quantities().outstanding()
                    || !line.sku().equals(requested.observedSku()) || !line.lotCode().equals(requested.observedLotCode()))
                throw new TransferWriteConflict();
            transfers.pendingReturn(organizationId,transferId,line.id())
                    .ifPresent(pending -> returnRequestsByLine.put(line.id(),pending));
            stockLines.add(new TransferReceiptLine(line.id(),line.destinationLocationId(),line.productId(),line.lotId(),
                    requested.expectedDestinationVersion(),requested.quantity()));
            quantityByLine.put(line.id(),requested.quantity());
        }
        var posted=stock.receiveForTransfer(actor,organizationId,current.destinationStoreId(),normalized.requestId(),transferId,
                normalized.physicallyReceivedAt(),normalized.reason(),normalized.evidenceReference(),stockLines);
        TransferStatus nextStatus=receiptStatus(current.status(),detail.lines(),quantityByLine);
        long version=nextVersion(current.version());
        UUID commandId=UUID.randomUUID();
        var outcome=new CommandOutcome(transferId,nextStatus,version,commandId,posted.movementId());
        recordCommand(organizationId,transferId,actor,member.membershipId(),RECEIVE,normalized.requestId(),digest,outcome,
                current.destinationStoreId(),posted.commandId(),InventoryOperation.TRANSFER_IN.name(),commandId);
        returnRequestsByLine.values().forEach(pending -> recordReturnLifecycle(organizationId,transferId,actor,
                member.membershipId(),commandId,pending,"RETURN_SUPERSEDED","SUPERSEDED",pending.settledQuantity(),
                pending.quantity(),RECEIVE,null,normalized.reason(),normalized.evidenceReference()));
        UUID documentId=UUID.randomUUID();
        transfers.recordPhysicalDocument(new PhysicalDocument(documentId,organizationId,transferId,current.sourceStoreId(),
                current.destinationStoreId(),current.destinationStoreId(),commandId,"DESTINATION","RECEIPT",
                normalized.documentReference(),normalized.reason(),normalized.evidenceReference(),actor,member.membershipId()));
        var postedByLine=posted.lines().stream().collect(java.util.stream.Collectors.toMap(
                InventoryService.TransferStockLineResult::lineId,line -> line));
        for(var requested:normalized.lines()) {
            var line=byId.get(requested.lineId());
            var stockLine=postedByLine.get(line.id());
            if(stockLine==null || stockLine.quantity()!=requested.quantity()) throw new TransferWriteConflict();
            transfers.recordPhysicalDocumentLine(new PhysicalDocumentLine(organizationId,transferId,documentId,line.id(),
                    "RECEIPT",line.lineNumber(),requested.documentLineReference(),requested.quantity(),
                    stockLine.allocationId()==null?"AVAILABLE":"QUARANTINED",requested.observedSku(),
                    requested.observedLotCode(),null));
            transfers.appendCustodyMovement(new TransferRepository.CustodyAppend(UUID.randomUUID(),organizationId,transferId,
                    line.id(),line.version(),com.stockrescue.transfers.domain.TransferMovementType.RECEIPT,commandId,actor,
                    member.membershipId(),requested.quantity(),current.destinationStoreId(),posted.movementId(),
                    stockLine.positionId(),normalized.reason(),normalized.evidenceReference()));
        }
        transfers.advanceStatus(organizationId,transferId,current.version(),nextStatus,commandId,actor,member.membershipId(),
                normalized.reason(),normalized.evidenceReference(),null,null);
        audit.securityChange(actor,organizationId,"transfer",transferId,"TRANSFER_RECEIVED",
                Map.of("outcome","SUCCEEDED","before",current.status(),"after",nextStatus,"version",version,
                        "commandId",commandId,"stockMovementId",posted.movementId(),"documentReference",normalized.documentReference(),
                        "lineCount",normalized.lines().size()),normalized.reason());
        return outcome;
    }

    @Transactional
    public CommandOutcome openDiscrepancy(UUID actor,UUID organizationId,UUID transferId,DiscrepancyRequest request) {
        var normalized=TransferRequestNormalizer.normalize(request);
        var identity=identity(organizationId,transferId);
        var member=requireDiscrepancyManager(actor,organizationId,identity);
        byte[] digest=commandFingerprint(organizationId,OPEN_DISCREPANCY,transferId,normalized);
        transfers.lockCommand(organizationId,actor,OPEN_DISCREPANCY,normalized.requestId());
        var replay=replay(actor,organizationId,OPEN_DISCREPANCY,transferId,normalized.requestId(),digest);
        if(replay!=null) return replay;
        lockStores(organizationId,identity.sourceStoreId(),identity.destinationStoreId());
        var current=transfers.lockTransferForCommand(organizationId,transferId);
        requireSettleable(current,normalized.expectedVersion());
        var detail=transfers.detail(organizationId,transferId).orElseThrow(OrganizationUnavailableException::new);
        if(normalized.lineId()!=null && detail.lines().stream().noneMatch(line -> line.id().equals(normalized.lineId())))
            throw new OrganizationUnavailableException();
        UUID commandId=UUID.randomUUID();
        long version=nextVersion(current.version());
        var outcome=new CommandOutcome(transferId,TransferStatus.RECONCILIATION_REQUIRED,version,commandId,null);
        recordCommand(organizationId,transferId,actor,member.membershipId(),OPEN_DISCREPANCY,normalized.requestId(),digest,
                outcome,null,null,null,commandId);
        transfers.recordReconciliationEvidence(new TransferRepository.ReconciliationEvidence(UUID.randomUUID(),organizationId,
                transferId,normalized.lineId(),commandId,actor,member.membershipId(),"DISCREPANCY",
                json.writeValueAsString(Map.of("state",normalized.expectedState())),
                json.writeValueAsString(Map.of("state",normalized.observedState())),normalized.reason(),normalized.evidenceReference()));
        transfers.advanceStatus(organizationId,transferId,current.version(),TransferStatus.RECONCILIATION_REQUIRED,
                commandId,actor,member.membershipId(),normalized.reason(),normalized.evidenceReference(),null,null);
        audit.securityChange(actor,organizationId,"transfer",transferId,"TRANSFER_DISCREPANCY_OPENED",
                Map.of("outcome","SUCCEEDED","before",current.status(),"after",TransferStatus.RECONCILIATION_REQUIRED,
                        "version",version,"commandId",commandId,"scope",normalized.lineId()==null?"TRANSFER":normalized.lineId().toString()),
                normalized.reason());
        return outcome;
    }

    @Transactional
    public CommandOutcome recordDamage(UUID actor,UUID organizationId,UUID transferId,DamageRequest request) {
        var normalized=TransferRequestNormalizer.normalize(request);
        var identity=identity(organizationId,transferId);
        var member=requireTransferSettlementAction(actor,organizationId,identity,StoreAction.MANAGE_TRANSFER_DISCREPANCIES,
                ()->access.requireTransferStore(actor,organizationId,identity.destinationStoreId(),
                        StoreAction.MANAGE_TRANSFER_DISCREPANCIES,true));
        byte[] digest=commandFingerprint(organizationId,CONFIRM_LOSS,transferId,normalized);
        transfers.lockCommand(organizationId,actor,CONFIRM_LOSS,normalized.requestId());
        var replay=replay(actor,organizationId,CONFIRM_LOSS,transferId,normalized.requestId(),digest);
        if(replay!=null) return replay;
        lockStores(organizationId,identity.sourceStoreId(),identity.destinationStoreId());
        transfers.lockDocumentIdentity(organizationId,identity.destinationStoreId(),"DAMAGE",normalized.documentReference());
        if(transfers.physicalDocumentExists(organizationId,identity.destinationStoreId(),"DAMAGE",normalized.documentReference()))
            throw new InventoryFailure(InventoryFailure.Kind.DOCUMENT_ALREADY_RECEIVED);
        var current=transfers.lockTransferForCommand(organizationId,transferId);
        requireSettleable(current,normalized.expectedVersion());
        var detail=transfers.detail(organizationId,transferId).orElseThrow(OrganizationUnavailableException::new);
        var byId=detail.lines().stream().collect(java.util.stream.Collectors.toMap(TransferRepository.TransferLine::id,line -> line));
        var returnRequestsByLine=new java.util.HashMap<UUID,TransferRepository.PendingReturn>();
        for(var requested:normalized.lines()) {
            var line=byId.get(requested.lineId());
            if(line==null || requested.quantity()>line.quantities().outstanding()) throw new TransferWriteConflict();
            transfers.pendingReturn(organizationId,transferId,line.id())
                    .ifPresent(pending -> returnRequestsByLine.put(line.id(),pending));
        }
        long version=nextVersion(current.version());
        UUID commandId=UUID.randomUUID();
        var outcome=new CommandOutcome(transferId,TransferStatus.RECONCILIATION_REQUIRED,version,commandId,null);
        recordCommand(organizationId,transferId,actor,member.membershipId(),CONFIRM_LOSS,normalized.requestId(),digest,
                outcome,null,null,null,commandId);
        returnRequestsByLine.values().forEach(pending -> recordReturnLifecycle(organizationId,transferId,actor,
                member.membershipId(),commandId,pending,"RETURN_SUPERSEDED","SUPERSEDED",pending.settledQuantity(),
                pending.quantity(),CONFIRM_LOSS,null,normalized.reason(),normalized.evidenceReference()));
        UUID documentId=UUID.randomUUID();
        transfers.recordPhysicalDocument(new PhysicalDocument(documentId,organizationId,transferId,current.sourceStoreId(),
                current.destinationStoreId(),current.destinationStoreId(),commandId,"DESTINATION","DAMAGE",
                normalized.documentReference(),normalized.reason(),normalized.evidenceReference(),actor,member.membershipId()));
        for(var requested:normalized.lines()) {
            var line=byId.get(requested.lineId());
            transfers.recordPhysicalDocumentLine(new PhysicalDocumentLine(organizationId,transferId,documentId,line.id(),
                    "DAMAGE",line.lineNumber(),requested.documentLineReference(),requested.quantity(),"DAMAGED",
                    line.sku(),line.lotCode(),null));
            transfers.appendCustodyMovement(new TransferRepository.CustodyAppend(UUID.randomUUID(),organizationId,transferId,
                    line.id(),line.version(),com.stockrescue.transfers.domain.TransferMovementType.LOSS,commandId,actor,
                    member.membershipId(),requested.quantity(),null,null,null,normalized.reason(),normalized.evidenceReference()));
            transfers.recordReconciliationEvidence(new TransferRepository.ReconciliationEvidence(UUID.randomUUID(),organizationId,
                    transferId,line.id(),commandId,actor,member.membershipId(),"DISCREPANCY",
                    json.writeValueAsString(Map.of("outstanding",line.quantities().outstanding())),
                    json.writeValueAsString(Map.of("confirmedLoss",requested.quantity(),"reasonCode",normalized.reasonCode())),
                    normalized.reason(),normalized.evidenceReference()));
        }
        transfers.advanceStatus(organizationId,transferId,current.version(),TransferStatus.RECONCILIATION_REQUIRED,
                commandId,actor,member.membershipId(),normalized.reason(),normalized.evidenceReference(),null,null);
        audit.securityChange(actor,organizationId,"transfer",transferId,"TRANSFER_DAMAGE_CONFIRMED",
                Map.of("outcome","SUCCEEDED","before",current.status(),"after",TransferStatus.RECONCILIATION_REQUIRED,
                        "version",version,"commandId",commandId,"reasonCode",normalized.reasonCode(),
                        "documentReference",normalized.documentReference()),normalized.reason());
        return outcome;
    }

    @Transactional
    public CommandOutcome requestReturn(UUID actor,UUID organizationId,UUID transferId,RequestReturn request) {
        var normalized=TransferRequestNormalizer.normalize(request);
        var identity=identity(organizationId,transferId);
        var member=requireTransferSettlementAction(actor,organizationId,identity,StoreAction.REQUEST_TRANSFER_RETURN,
                ()->access.requireTransferStore(actor,organizationId,identity.destinationStoreId(),
                        StoreAction.REQUEST_TRANSFER_RETURN,true));
        byte[] digest=commandFingerprint(organizationId,REQUEST_RETURN,transferId,normalized);
        transfers.lockCommand(organizationId,actor,REQUEST_RETURN,normalized.requestId());
        var replay=replay(actor,organizationId,REQUEST_RETURN,transferId,normalized.requestId(),digest);
        if(replay!=null) return replay;
        lockStores(organizationId,identity.sourceStoreId(),identity.destinationStoreId());
        var current=transfers.lockTransferForCommand(organizationId,transferId);
        requireSettleable(current,normalized.expectedVersion());
        var detail=transfers.detail(organizationId,transferId).orElseThrow(OrganizationUnavailableException::new);
        var byId=detail.lines().stream().collect(java.util.stream.Collectors.toMap(TransferRepository.TransferLine::id,line -> line));
        var requestLines=new ArrayList<Map.Entry<TransferRepository.TransferLine,Long>>(normalized.lines().size());
        for(var requested:normalized.lines()) {
            var line=byId.get(requested.lineId());
            if(line==null || line.quantities().outstanding()<=0 || transfers.pendingReturn(organizationId,transferId,line.id()).isPresent())
                throw new TransferWriteConflict();
            requestLines.add(Map.entry(line,line.quantities().outstanding()));
        }
        long version=nextVersion(current.version());
        UUID commandId=UUID.randomUUID();
        var outcome=new CommandOutcome(transferId,TransferStatus.RECONCILIATION_REQUIRED,version,commandId,null);
        recordCommand(organizationId,transferId,actor,member.membershipId(),REQUEST_RETURN,normalized.requestId(),digest,
                outcome,null,null,null,commandId);
        for(var requested:requestLines) {
            var line=requested.getKey();
            transfers.recordReconciliationEvidence(new TransferRepository.ReconciliationEvidence(UUID.randomUUID(),organizationId,
                    transferId,line.id(),commandId,actor,member.membershipId(),"RETURN_REQUEST",
                    json.writeValueAsString(Map.of("outstanding",requested.getValue())),
                    json.writeValueAsString(Map.of("requestReference",normalized.requestReference(),
                            "requestedQuantity",requested.getValue())),normalized.reason(),normalized.evidenceReference()));
        }
        transfers.advanceStatus(organizationId,transferId,current.version(),TransferStatus.RECONCILIATION_REQUIRED,
                commandId,actor,member.membershipId(),normalized.reason(),normalized.evidenceReference(),null,null);
        audit.securityChange(actor,organizationId,"transfer",transferId,"TRANSFER_RETURN_REQUESTED",
                Map.of("outcome","SUCCEEDED","before",current.status(),"after",TransferStatus.RECONCILIATION_REQUIRED,
                        "version",version,"commandId",commandId,"requestReference",normalized.requestReference(),
                        "lineCount",requestLines.size()),normalized.reason());
        return outcome;
    }

    @Transactional
    public CommandOutcome confirmReturn(UUID actor,UUID organizationId,UUID transferId,ConfirmReturnRequest request) {
        var normalized=TransferRequestNormalizer.normalize(request);
        var identity=identity(organizationId,transferId);
        var member=access.requireTransferSettlementStore(actor,organizationId,identity.sourceStoreId(),StoreAction.RECEIVE_STOCK);
        byte[] digest=commandFingerprint(organizationId,CONFIRM_RETURN,transferId,normalized);
        transfers.lockCommand(organizationId,actor,CONFIRM_RETURN,normalized.requestId());
        var replay=replay(actor,organizationId,CONFIRM_RETURN,transferId,normalized.requestId(),digest);
        if(replay!=null) return replay;
        lockStores(organizationId,identity.sourceStoreId(),identity.destinationStoreId());
        transfers.lockDocumentIdentity(organizationId,identity.sourceStoreId(),"RETURN",normalized.documentReference());
        if(transfers.physicalDocumentExists(organizationId,identity.sourceStoreId(),"RETURN",normalized.documentReference()))
            throw new InventoryFailure(InventoryFailure.Kind.DOCUMENT_ALREADY_RECEIVED);
        var current=transfers.lockTransferForCommand(organizationId,transferId);
        if(current.version()!=normalized.expectedVersion() || current.status()!=TransferStatus.RECONCILIATION_REQUIRED)
            throw new TransferWriteConflict();
        var detail=transfers.detail(organizationId,transferId).orElseThrow(OrganizationUnavailableException::new);
        var byId=detail.lines().stream().collect(java.util.stream.Collectors.toMap(TransferRepository.TransferLine::id,line -> line));
        var stockLines=new ArrayList<TransferReturnLine>(normalized.lines().size());
        var quantityByLine=new java.util.HashMap<UUID,Long>();
        var requestEvidenceByLine=new java.util.HashMap<UUID,UUID>();
        var returnRequestsByLine=new java.util.HashMap<UUID,TransferRepository.PendingReturn>();
        for(var requested:normalized.lines()) {
            var line=byId.get(requested.lineId());
            var pending=transfers.pendingReturn(organizationId,transferId,requested.lineId()).orElseThrow(TransferWriteConflict::new);
            if(line==null || !pending.requestReference().equals(normalized.requestReference())
                    || requested.quantity()>pending.quantity() || requested.quantity()>line.quantities().outstanding()
                    || !line.sku().equals(requested.observedSku()) || !line.lotCode().equals(requested.observedLotCode()))
                throw new TransferWriteConflict();
            stockLines.add(new TransferReturnLine(line.id(),line.sourcePositionId(),requested.expectedSourceVersion(),requested.quantity()));
            quantityByLine.put(line.id(),requested.quantity());
            requestEvidenceByLine.put(line.id(),pending.evidenceId());
            returnRequestsByLine.put(line.id(),pending);
        }
        var posted=stock.returnForTransfer(actor,organizationId,current.sourceStoreId(),normalized.requestId(),transferId,
                normalized.reason(),normalized.evidenceReference(),stockLines);
        long version=nextVersion(current.version());
        UUID commandId=UUID.randomUUID();
        var outcome=new CommandOutcome(transferId,TransferStatus.RECONCILIATION_REQUIRED,version,commandId,posted.movementId());
        recordCommand(organizationId,transferId,actor,member.membershipId(),CONFIRM_RETURN,normalized.requestId(),digest,
                outcome,current.sourceStoreId(),posted.commandId(),InventoryOperation.TRANSFER_RETURN.name(),commandId);
        UUID documentId=UUID.randomUUID();
        transfers.recordPhysicalDocument(new PhysicalDocument(documentId,organizationId,transferId,current.sourceStoreId(),
                current.destinationStoreId(),current.sourceStoreId(),commandId,"SOURCE","RETURN",normalized.documentReference(),
                normalized.reason(),normalized.evidenceReference(),actor,member.membershipId()));
        var postedByLine=posted.lines().stream().collect(java.util.stream.Collectors.toMap(
                InventoryService.TransferStockLineResult::lineId,line -> line));
        for(var requested:normalized.lines()) {
            var line=byId.get(requested.lineId());
            var stockLine=postedByLine.get(line.id());
            long quantity=quantityByLine.get(line.id());
            if(stockLine==null || stockLine.quantity()!=quantity) throw new TransferWriteConflict();
            transfers.recordPhysicalDocumentLine(new PhysicalDocumentLine(organizationId,transferId,documentId,line.id(),
                    "RETURN",line.lineNumber(),requested.documentLineReference(),quantity,"RETURNED",requested.observedSku(),
                    requested.observedLotCode(),null,requestEvidenceByLine.get(line.id())));
            var returnRequest=returnRequestsByLine.get(line.id());
            long settledQuantity;
            try {
                settledQuantity=Math.addExact(returnRequest.settledQuantity(),quantity);
            } catch(ArithmeticException overflow) {
                throw new TransferWriteConflict();
            }
            boolean fulfilled=settledQuantity==returnRequest.requestedQuantity();
            recordReturnLifecycle(organizationId,transferId,actor,member.membershipId(),commandId,returnRequest,
                    fulfilled?"RETURN_FULFILLED":"RETURN_PARTIAL",fulfilled?"FULFILLED":"PARTIALLY_FULFILLED",
                    settledQuantity,returnRequest.requestedQuantity()-settledQuantity,CONFIRM_RETURN,documentId,
                    normalized.reason(),normalized.evidenceReference());
            transfers.appendCustodyMovement(new TransferRepository.CustodyAppend(UUID.randomUUID(),organizationId,transferId,
                    line.id(),line.version(),com.stockrescue.transfers.domain.TransferMovementType.RETURN,commandId,actor,
                    member.membershipId(),quantity,current.sourceStoreId(),posted.movementId(),stockLine.positionId(),
                    normalized.reason(),normalized.evidenceReference()));
            transfers.recordReconciliationEvidence(new TransferRepository.ReconciliationEvidence(UUID.randomUUID(),organizationId,
                    transferId,line.id(),commandId,actor,member.membershipId(),"CUSTODY_RECONCILIATION",
                    json.writeValueAsString(Map.of("outstanding",line.quantities().outstanding())),
                    json.writeValueAsString(Map.of("physicallyReturned",quantity)),normalized.reason(),normalized.evidenceReference()));
        }
        transfers.advanceStatus(organizationId,transferId,current.version(),TransferStatus.RECONCILIATION_REQUIRED,
                commandId,actor,member.membershipId(),normalized.reason(),normalized.evidenceReference(),null,null);
        audit.securityChange(actor,organizationId,"transfer",transferId,"TRANSFER_RETURN_CONFIRMED",
                Map.of("outcome","SUCCEEDED","before",current.status(),"after",TransferStatus.RECONCILIATION_REQUIRED,
                        "version",version,"commandId",commandId,"stockMovementId",posted.movementId(),
                        "documentReference",normalized.documentReference()),normalized.reason());
        return outcome;
    }

    @Transactional
    public CommandOutcome closeDiscrepancy(UUID actor,UUID organizationId,UUID transferId,DecisionRequest request) {
        var normalized=TransferRequestNormalizer.normalize(request);
        var identity=identity(organizationId,transferId);
        var member=requireDiscrepancyManager(actor,organizationId,identity);
        byte[] digest=commandFingerprint(organizationId,CLOSE,transferId,normalized);
        transfers.lockCommand(organizationId,actor,CLOSE,normalized.requestId());
        var replay=replay(actor,organizationId,CLOSE,transferId,normalized.requestId(),digest);
        if(replay!=null) return replay;
        lockStores(organizationId,identity.sourceStoreId(),identity.destinationStoreId());
        var current=transfers.lockTransferForCommand(organizationId,transferId);
        if(current.version()!=normalized.expectedVersion() || current.status()!=TransferStatus.RECONCILIATION_REQUIRED)
            throw new TransferWriteConflict();
        var detail=transfers.detail(organizationId,transferId).orElseThrow(OrganizationUnavailableException::new);
        if(detail.lines().isEmpty() || detail.lines().stream().anyMatch(line -> line.quantities().outstanding()!=0)
                || !transfers.hasReconciliationEvidence(organizationId,transferId)) throw new TransferWriteConflict();
        boolean fullyReceived=detail.lines().stream().allMatch(line ->
                line.quantities().physicallyReceived()==line.quantities().dispatched()
                        && line.quantities().confirmedLoss()==0 && line.quantities().physicallyReturned()==0);
        TransferStatus resolvedStatus=fullyReceived?TransferStatus.RECEIVED:TransferStatus.CLOSED_WITH_DISCREPANCY;
        long version=nextVersion(current.version());
        UUID commandId=UUID.randomUUID();
        var outcome=new CommandOutcome(transferId,resolvedStatus,version,commandId,null);
        recordCommand(organizationId,transferId,actor,member.membershipId(),CLOSE,normalized.requestId(),digest,
                outcome,null,null,null,commandId);
        transfers.recordReconciliationEvidence(new TransferRepository.ReconciliationEvidence(UUID.randomUUID(),organizationId,
                transferId,null,commandId,actor,member.membershipId(),"CUSTODY_RECONCILIATION",
                json.writeValueAsString(Map.of("outstanding",0)),json.writeValueAsString(Map.of("closed",true,
                        "resolvedStatus",resolvedStatus,"settlement",fullyReceived?"FULL_PHYSICAL_RECEIPT":"LOSS_OR_RETURN")),
                normalized.reason(),normalized.evidenceReference()));
        transfers.advanceStatus(organizationId,transferId,current.version(),resolvedStatus,
                commandId,actor,member.membershipId(),normalized.reason(),normalized.evidenceReference(),null,null);
        audit.securityChange(actor,organizationId,"transfer",transferId,
                fullyReceived?"TRANSFER_RECONCILIATION_RESOLVED":"TRANSFER_DISCREPANCY_CLOSED",
                Map.of("outcome","SUCCEEDED","before",current.status(),"after",resolvedStatus,
                        "version",version,"commandId",commandId,"lineCount",detail.lines().size()),normalized.reason());
        return outcome;
    }

    @Transactional(readOnly = true)
    public TransferDetail detail(UUID actor, UUID organizationId, UUID transferId) {
        return queries.detail(actor, organizationId, transferId);
    }

    @Transactional(readOnly = true)
    public TransferQueryService.TransferCapabilities capabilities(UUID actor, UUID organizationId, UUID transferId) {
        return queries.capabilities(actor, organizationId, transferId);
    }

    @Transactional(readOnly = true)
    public TransferSettlementPositions settlementPositions(UUID actor, UUID organizationId, UUID transferId) {
        var detail = queries.historicalSettlementDetail(actor, organizationId, transferId);
        var sourceRequests = detail.lines().stream().map(line ->
                new TransferSettlementPositionRequest(line.id(), line.sourcePositionId(), null)).toList();
        var destinationRequests = detail.lines().stream().map(line -> new TransferSettlementPositionRequest(line.id(), null,
                new PositionIdentity(line.destinationLocationId(), line.productId(), line.lotId()))).toList();
        var source = stock.transferSettlementPositionVersions(actor, organizationId, detail.transfer().sourceStoreId(), sourceRequests);
        var destination = stock.transferSettlementPositionVersions(actor, organizationId, detail.transfer().destinationStoreId(), destinationRequests);
        return new TransferSettlementPositions(source, destination);
    }

    @Transactional(readOnly = true)
    public boolean canCancel(UUID actor, UUID organizationId, UUID transferId) {
        access.organization(actor, organizationId);
        var identity = identity(organizationId, transferId);
        if (!Set.of(TransferStatus.REQUESTED, TransferStatus.APPROVED, TransferStatus.PICKING, TransferStatus.PICKED)
                .contains(identity.status())) return false;
        StoreAction action = cancellationAction(actor, identity.createdByUserId(), identity.status(), false);
        try {
            access.requireTransferStore(actor, organizationId, identity.sourceStoreId(), action, false);
            return true;
        } catch (OrganizationUnavailableException | AccessDeniedException denied) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public TransferRepository.Page<TransferRepository.TransferSummary> page(UUID actor, UUID organizationId,
            UUID storeId, TransferStatus status, int offset, int limit) {
        return queries.page(actor, organizationId, storeId, status, offset, limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.TransferSummary> page(UUID actor, UUID organizationId,
            UUID storeId, TransferStatus status, String cursor, int limit) {
        return queries.page(actor, organizationId, storeId, status, cursor, limit);
    }

    @Transactional(readOnly = true)
    public Recovery recover(UUID actor, UUID organizationId, String operation, UUID requestId) {
        return queries.recover(actor, organizationId, operation, requestId);
    }

    public byte[] commandFingerprint(UUID organizationId, String operation, UUID transferId, JsonNode payload) {
        return fingerprints.fromPayload(organizationId, operation, transferId, payload);
    }

    private byte[] commandFingerprint(UUID organizationId, String operation, UUID transferId, Object request) {
        return fingerprints.forRequest(organizationId, operation, transferId, request);
    }

    @Transactional
    public UUID authorizeCommandIntent(UUID actor, UUID organizationId, String operation, UUID transferId, JsonNode payload) {
        if (operation == null || organizationId == null || actor == null || !COMMAND_OPERATIONS.contains(operation) || payload == null || !payload.isObject()
                || (operation.equals(CREATE) != (transferId == null)))
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        if (operation.equals(CREATE)) {
            UUID sourceStoreId = payloadUuid(payload, "sourceStoreId");
            return access.requireTransferStore(actor, organizationId, sourceStoreId, StoreAction.REQUEST_TRANSFER, true).membershipId();
        }
        var identity = identity(organizationId, transferId);
        return switch (operation) {
            case APPROVE, REJECT -> access.requireTransferStore(actor, organizationId, identity.sourceStoreId(), StoreAction.APPROVE_TRANSFER, true).membershipId();
            case CANCEL -> {
                UUID requestId = payloadUuid(payload, "requestId");
                boolean ownerRetry = actor.equals(identity.createdByUserId())
                        && transfers.command(organizationId, actor, CANCEL, requestId).isPresent();
                StoreAction action = cancellationAction(actor, identity.createdByUserId(), identity.status(), ownerRetry);
                yield access.requireTransferStore(actor, organizationId, identity.sourceStoreId(), action, true).membershipId();
            }
            case START_PICK, PICK -> access.requireTransferStore(actor, organizationId, identity.sourceStoreId(), StoreAction.PICK_TRANSFER, true).membershipId();
            case DISPATCH -> access.requireTransferStore(actor, organizationId, identity.sourceStoreId(), StoreAction.DISPATCH_TRANSFER, true).membershipId();
            case RECEIVE -> access.requireTransferSettlementStore(actor, organizationId, identity.destinationStoreId(), StoreAction.RECEIVE_STOCK).membershipId();
            case CONFIRM_RETURN -> access.requireTransferSettlementStore(actor, organizationId, identity.sourceStoreId(), StoreAction.RECEIVE_STOCK).membershipId();
            case REQUEST_RETURN -> requireTransferSettlementAction(actor,organizationId,identity,StoreAction.REQUEST_TRANSFER_RETURN,
                    ()->access.requireTransferStore(actor,organizationId,identity.destinationStoreId(),
                            StoreAction.REQUEST_TRANSFER_RETURN,true)).membershipId();
            case CONFIRM_LOSS -> requireTransferSettlementAction(actor,organizationId,identity,StoreAction.MANAGE_TRANSFER_DISCREPANCIES,
                    ()->access.requireTransferStore(actor,organizationId,identity.destinationStoreId(),
                            StoreAction.MANAGE_TRANSFER_DISCREPANCIES,true)).membershipId();
            case OPEN_DISCREPANCY, CLOSE -> requireDiscrepancyManager(actor, organizationId, identity).membershipId();
            default -> throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        };
    }

    public boolean canReadCommandIntent(UUID actor, UUID organizationId, String operation, UUID transferId, JsonNode payload) {
        return queries.canReadCommandIntent(actor, organizationId, operation, transferId, payload);
    }

    private static UUID payloadUuid(JsonNode payload, String field) {
        String value = payload.path(field).asString();
        if (value == null) throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST); }
    }

    private static StoreAction cancellationAction(UUID actor, UUID createdBy, TransferStatus status, boolean ownerRetry) {
        return actor.equals(createdBy) && (status == TransferStatus.REQUESTED || ownerRetry)
                ? StoreAction.REQUEST_TRANSFER : StoreAction.CANCEL_TRANSFER;
    }

    @Transactional(readOnly = true)
    public TransferRepository.Page<TransferRepository.CustodyMovement> custodyTimeline(UUID actor,UUID organizationId,
            UUID transferId,int offset,int limit) {
        return queries.custodyTimeline(actor, organizationId, transferId, offset, limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.CustodyMovement> custodyTimeline(UUID actor,UUID organizationId,
            UUID transferId,String cursor,int limit) {
        return queries.custodyTimeline(actor,organizationId,transferId,cursor,limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.Page<TransferRepository.InTransitLine> inTransitAging(UUID actor,UUID organizationId,
            UUID storeId,int offset,int limit) {
        return queries.inTransitAging(actor, organizationId, storeId, offset, limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.InTransitLine> inTransitAging(UUID actor,UUID organizationId,
            UUID storeId,String cursor,int limit) {
        return queries.inTransitAging(actor,organizationId,storeId,cursor,limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.InTransitLine> inTransit(UUID actor,UUID organizationId,
            UUID transferId,String cursor,int limit) {
        return queries.transferInTransit(actor,organizationId,transferId,cursor,limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.Page<TransferRepository.Discrepancy> discrepancies(UUID actor,UUID organizationId,
            UUID storeId,int offset,int limit) {
        return queries.discrepancies(actor, organizationId, storeId, offset, limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.Discrepancy> discrepancies(UUID actor,UUID organizationId,
            UUID storeId,String cursor,int limit) {
        return queries.discrepancies(actor,organizationId,storeId,cursor,limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.Discrepancy> transferDiscrepancies(UUID actor,UUID organizationId,
            UUID transferId,String cursor,int limit) {
        return queries.transferDiscrepancies(actor,organizationId,transferId,cursor,limit);
    }

    @Transactional(readOnly = true)
    public List<TransferRepository.ActiveReturn> activeReturns(UUID actor, UUID organizationId, UUID transferId) {
        return queries.activeReturns(actor, organizationId, transferId);
    }

    private com.stockrescue.access.application.OrganizationAccess requireDiscrepancyManager(UUID actor,UUID organizationId,
            TransferIdentity identity) {
        RuntimeException denied=null;
        for(UUID store:List.of(identity.destinationStoreId(),identity.sourceStoreId())) {
            try {
                return access.requireTransferStore(actor,organizationId,store,StoreAction.MANAGE_TRANSFER_DISCREPANCIES,true);
            } catch(OrganizationUnavailableException | AccessDeniedException unavailable) {
                denied=unavailable;
            }
        }
        if(denied!=null) {
            RuntimeException currentDenied=denied;
            return access.historicalTransferSettlementAdmin(actor,organizationId,identity.sourceStoreId(),
                    identity.destinationStoreId(),StoreAction.MANAGE_TRANSFER_DISCREPANCIES)
                    .orElseThrow(()->currentDenied);
        }
        throw new OrganizationUnavailableException();
    }

    private com.stockrescue.access.application.OrganizationAccess requireTransferSettlementAction(UUID actor,UUID organizationId,
            TransferIdentity identity,StoreAction action,java.util.function.Supplier<com.stockrescue.access.application.OrganizationAccess> normalAuthorization) {
        try {
            return normalAuthorization.get();
        } catch(OrganizationUnavailableException | AccessDeniedException denied) {
            return access.historicalTransferSettlementAdmin(actor,organizationId,identity.sourceStoreId(),
                    identity.destinationStoreId(),action).orElseThrow(()->denied);
        }
    }

    private static void requireSettleable(TransferRepository.LockedTransfer current,long expectedVersion) {
        if(current.version()!=expectedVersion || current.status()!=TransferStatus.IN_TRANSIT
                && current.status()!=TransferStatus.PARTIALLY_RECEIVED
                && current.status()!=TransferStatus.RECONCILIATION_REQUIRED) throw new TransferWriteConflict();
    }

    private static TransferStatus receiptStatus(TransferStatus current,List<TransferRepository.TransferLine> lines,
            Map<UUID,Long> receivedNow) {
        // A clean receipt settles custody quantities, but it cannot resolve an already-open review by itself.
        if(current==TransferStatus.RECONCILIATION_REQUIRED) return current;
        boolean settled=true;
        boolean allPhysicallyReceived=true;
        for(var line:lines) {
            long quantity=receivedNow.getOrDefault(line.id(),0L);
            if(quantity>line.quantities().outstanding()) throw new TransferWriteConflict();
            long remaining=line.quantities().outstanding()-quantity;
            if(remaining!=0) settled=false;
            long received=Math.addExact(line.quantities().physicallyReceived(),quantity);
            if(remaining!=0 || received!=line.quantities().dispatched()
                    || line.quantities().confirmedLoss()!=0 || line.quantities().physicallyReturned()!=0)
                allPhysicallyReceived=false;
        }
        if(settled && allPhysicallyReceived) return TransferStatus.RECEIVED;
        return TransferStatus.PARTIALLY_RECEIVED;
    }

    private TransferRoute requireCurrentTransferRoute(UUID organizationId, UUID sourceStoreId, UUID destinationStoreId,
            List<TransferRepository.TransferLine> lines) {
        if (lines.isEmpty()) throw new TransferWriteConflict();
        var positions = lines.stream().map(line -> inventory.position(organizationId, sourceStoreId,
                line.sourcePositionId(), false).orElseThrow(OrganizationUnavailableException::new)).toList();
        TransferRoute route = null;
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            var position = positions.get(index);
            var current = lanes.requireTransferRoute(organizationId, sourceStoreId, destinationStoreId,
                    position.locationId(), line.destinationLocationId());
            if (route != null && !route.equals(current)) throw new TransferWriteConflict();
            route = current;
        }
        inventory.lockActiveContexts(organizationId, sourceStoreId, positions.stream()
                .map(position -> new PositionIdentity(position.locationId(), position.productId(), position.lotId())).toList());
        for (int index = 0; index < lines.size(); index++) {
            lanes.lockTransferLocations(organizationId, sourceStoreId, destinationStoreId,
                    positions.get(index).locationId(), lines.get(index).destinationLocationId(), route);
        }
        return route;
    }

    private static void requireApprovedRoute(UUID approvedLaneId, UUID approvedRevisionId, TransferRoute route) {
        if (approvedLaneId == null || approvedRevisionId == null || route == null
                || !approvedLaneId.equals(route.laneId()) || !approvedRevisionId.equals(route.revisionId()))
            throw new TransferWriteConflict();
    }

    private static long reservedPositionVersion(TransferRepository.TransferLine line) {
        try {
            return Math.incrementExact(line.expectedSourceVersion());
        } catch (ArithmeticException overflow) {
            throw new TransferWriteConflict();
        }
    }

    private TransferIdentity identity(UUID organizationId, UUID transferId) {
        return transfers.identity(organizationId, transferId).orElseThrow(OrganizationUnavailableException::new);
    }

    private void recordReturnLifecycle(UUID organizationId,UUID transferId,UUID actor,UUID membershipId,UUID commandId,
            TransferRepository.PendingReturn request,String evidenceType,String state,long settledQuantity,
            long unsettledQuantity,String relatedOperation,UUID documentId,String reason,String evidenceReference) {
        var expected=Map.of("returnRequestEvidenceId",request.evidenceId().toString(),
                "previousState",request.state(),"requestedQuantity",request.requestedQuantity());
        var observed=new java.util.LinkedHashMap<String,Object>();
        observed.put("state",state);
        observed.put("requestEvidenceId",request.evidenceId().toString());
        observed.put("requestedQuantity",request.requestedQuantity());
        observed.put("settledQuantity",settledQuantity);
        observed.put("unsettledQuantity",unsettledQuantity);
        observed.put("relatedOperation",relatedOperation);
        observed.put("relatedCommandId",commandId.toString());
        if(documentId!=null) observed.put("physicalDocumentId",documentId.toString());
        transfers.recordReconciliationEvidence(new TransferRepository.ReconciliationEvidence(UUID.randomUUID(),
                organizationId,transferId,request.lineId(),commandId,actor,membershipId,evidenceType,
                json.writeValueAsString(expected),json.writeValueAsString(observed),reason,evidenceReference));
    }

    private void lockStores(UUID organizationId, UUID sourceStoreId, UUID destinationStoreId) {
        lanes.lockTransferStoreRows(organizationId,sourceStoreId,destinationStoreId);
        lockStoreAdvisories(organizationId,sourceStoreId,destinationStoreId);
    }

    private void lockStoreAdvisories(UUID organizationId, UUID sourceStoreId, UUID destinationStoreId) {
        List.of(sourceStoreId, destinationStoreId).stream().distinct().sorted().forEach(store -> inventory.lockStoreForWrite(organizationId, store));
    }

    private CommandOutcome replay(UUID actor, UUID organizationId, String operation, UUID transferId, UUID requestId, byte[] digest) {
        var prepared = commandIntents.lockCommandIntent(organizationId, actor, operation, requestId);
        if (prepared.isPresent()) {
            var intent = prepared.orElseThrow();
            if (!java.util.Objects.equals(intent.transferId(), transferId)
                    || !MessageDigest.isEqual(intent.fingerprint(), digest)
                    || intent.state().equals("REJECTED") || intent.state().equals("CONFLICTED"))
                throw new InventoryFailure(InventoryFailure.Kind.IDEMPOTENCY_CONFLICT);
        }
        var previous = transfers.command(organizationId, actor, operation, requestId);
        if (previous.isEmpty()) return null;
        var receipt = previous.orElseThrow();
        if ((!operation.equals(CREATE) && !java.util.Objects.equals(receipt.transferId(), transferId))
                || !MessageDigest.isEqual(receipt.payloadDigest(), digest))
            throw new InventoryFailure(InventoryFailure.Kind.IDEMPOTENCY_CONFLICT);
        return json.readValue(receipt.outcome(), CommandOutcome.class);
    }

    private void recordCommand(UUID organizationId, UUID transferId, UUID actor, UUID membershipId,
            String operation, UUID requestId, byte[] digest, CommandOutcome outcome,
            UUID stockStoreId, UUID stockCommandId, String stockOperation, UUID commandId) {
        var receipt = new TransferCommandReceipt(commandId, organizationId, transferId, actor, membershipId,
                operation, requestId, digest, json.writeValueAsString(outcome),
                CorrelationIds.requireValidOrNull(MDC.get("correlationId")), stockStoreId, stockCommandId, stockOperation);
        if (!transfers.recordCommand(receipt)) throw new InventoryFailure(InventoryFailure.Kind.IDEMPOTENCY_CONFLICT);
    }

    private static long nextVersion(long current) {
        try {
            return Math.incrementExact(current);
        } catch (ArithmeticException overflow) {
            throw new TransferWriteConflict();
        }
    }

    private static void expectVersionAndStatus(TransferStatus currentStatus, long currentVersion,
            long expectedVersion, TransferStatus expectedStatus) {
        if (currentVersion != expectedVersion || currentStatus != expectedStatus) throw new TransferWriteConflict();
    }

}
