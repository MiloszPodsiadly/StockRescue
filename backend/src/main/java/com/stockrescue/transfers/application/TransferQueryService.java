package com.stockrescue.transfers.application;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import com.stockrescue.access.application.AccessPolicy;
import com.stockrescue.access.application.EffectivePermissions.StoreAction;
import com.stockrescue.access.application.OrganizationUnavailableException;
import com.stockrescue.inventory.domain.InventoryFailure;
import com.stockrescue.transfers.domain.TransferStatus;
import com.stockrescue.transfers.persistence.TransferRepository;
import com.stockrescue.transfers.persistence.TransferRepository.TransferDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TransferQueryService {
    private final AccessPolicy access;
    private final TransferRepository transfers;
    private final ObjectMapper json;

    public TransferQueryService(AccessPolicy access, TransferRepository transfers, ObjectMapper json) {
        this.access = access;
        this.transfers = transfers;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public TransferDetail detail(UUID actor, UUID organizationId, UUID transferId) {
        return authorizedDetail(actor, organizationId, transferId);
    }

    @Transactional(readOnly = true)
    public TransferCapabilities capabilities(UUID actor, UUID organizationId, UUID transferId) {
        var detail = authorizedDetail(actor, organizationId, transferId);
        boolean sourceActive = access.isActiveStore(organizationId, detail.transfer().sourceStoreId());
        boolean destinationActive = access.isActiveStore(organizationId, detail.transfer().destinationStoreId());
        boolean administrator = access.organization(actor, organizationId).role()
                == com.stockrescue.access.application.OrganizationAccess.OrganizationRole.ORG_ADMIN;
        boolean receivingStatus = List.of(TransferStatus.IN_TRANSIT, TransferStatus.PARTIALLY_RECEIVED,
                TransferStatus.RECONCILIATION_REQUIRED).contains(detail.transfer().status());
        var activeReturns = transfers.activeReturns(organizationId, transferId);
        Set<UUID> activeReturnLines = activeReturns.stream().map(TransferRepository.ActiveReturn::lineId)
                .collect(java.util.stream.Collectors.toSet());
        boolean canReceivePhysicalSettlement = administrator && !destinationActive && receivingStatus
                && detail.transfer().outstandingQuantity().signum() > 0;
        boolean canConfirmPhysicalReturn = administrator && !sourceActive
                && detail.transfer().status() == TransferStatus.RECONCILIATION_REQUIRED
                && !activeReturns.isEmpty();
        var historicalActions = new ArrayList<HistoricalSettlementAction>();
        if (administrator && (!sourceActive || !destinationActive)) {
            boolean outstanding = detail.lines().stream().anyMatch(line -> line.quantities().outstanding() > 0);
            if (!destinationActive && receivingStatus && detail.lines().stream()
                    .anyMatch(line -> line.quantities().outstanding() > 0 && !activeReturnLines.contains(line.id())))
                historicalActions.add(HistoricalSettlementAction.REQUEST_RETURN);
            if (!destinationActive && receivingStatus && outstanding)
                historicalActions.add(HistoricalSettlementAction.CONFIRM_LOSS);
            if (receivingStatus) historicalActions.add(HistoricalSettlementAction.OPEN_DISCREPANCY);
            if (detail.transfer().status() == TransferStatus.RECONCILIATION_REQUIRED && !outstanding
                    && transfers.hasReconciliationEvidence(organizationId, transferId))
                historicalActions.add(HistoricalSettlementAction.CLOSE);
        }
        return new TransferCapabilities(
                access.currentStoreActions(actor, organizationId, detail.transfer().sourceStoreId()),
                access.currentStoreActions(actor, organizationId, detail.transfer().destinationStoreId()),
                canReceivePhysicalSettlement, canConfirmPhysicalReturn, historicalActions);
    }

    @Transactional(readOnly = true)
    public TransferDetail historicalSettlementDetail(UUID actor, UUID organizationId, UUID transferId) {
        var detail = authorizedDetail(actor, organizationId, transferId);
        if (access.organization(actor, organizationId).role()
                    != com.stockrescue.access.application.OrganizationAccess.OrganizationRole.ORG_ADMIN
                || access.isActiveStore(organizationId, detail.transfer().sourceStoreId())
                    && access.isActiveStore(organizationId, detail.transfer().destinationStoreId()))
            throw new OrganizationUnavailableException();
        return detail;
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.HistoricalTransfer> historicalPage(UUID actor,
            UUID organizationId, TransferStatus status, String cursor, int limit) {
        requireOrganizationAdministrator(actor, organizationId);
        return transfers.historicalPage(organizationId, status, cursor, limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.HistoricalParticipants historicalParticipants(UUID actor, UUID organizationId,
            UUID transferId) {
        if (access.organization(actor, organizationId).role()
                != com.stockrescue.access.application.OrganizationAccess.OrganizationRole.ORG_ADMIN) return null;
        authorizedDetail(actor, organizationId, transferId);
        return transfers.historicalParticipants(organizationId, transferId).orElse(null);
    }

    private void requireOrganizationAdministrator(UUID actor, UUID organizationId) {
        if (access.organization(actor, organizationId).role()
                != com.stockrescue.access.application.OrganizationAccess.OrganizationRole.ORG_ADMIN)
            throw new OrganizationUnavailableException();
    }

    @Transactional(readOnly = true)
    public boolean canReadCommandIntent(UUID actor, UUID organizationId, String operation, UUID transferId, JsonNode payload) {
        access.organization(actor, organizationId);
        try {
            authorizeCommandIntentRead(actor, organizationId, operation, transferId, payload);
            return true;
        } catch (OrganizationUnavailableException | AccessDeniedException denied) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public TransferRepository.Page<TransferRepository.TransferSummary> page(UUID actor, UUID organizationId,
            UUID storeId, TransferStatus status, int offset, int limit) {
        access.requireStore(actor, organizationId, storeId, StoreAction.READ_TRANSFERS, false);
        return transfers.page(organizationId, storeId, status, offset, limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.TransferSummary> page(UUID actor, UUID organizationId,
            UUID storeId, TransferStatus status, String cursor, int limit) {
        access.requireStore(actor, organizationId, storeId, StoreAction.READ_TRANSFERS, false);
        return transfers.page(organizationId, storeId, status, cursor, limit);
    }

    @Transactional(readOnly = true)
    public TransferService.Recovery recover(UUID actor, UUID organizationId, String operation, UUID requestId) {
        if (operation == null || requestId == null) throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        access.organization(actor, organizationId);
        var receipt = transfers.command(organizationId, actor, operation, requestId)
                .orElseThrow(OrganizationUnavailableException::new);
        var detail = transfers.detail(organizationId, receipt.transferId()).orElseThrow(OrganizationUnavailableException::new);
        authorizeRead(actor, organizationId, detail.transfer().sourceStoreId(), detail.transfer().destinationStoreId());
        return new TransferService.Recovery(receipt.operation(), receipt.requestId(),
                json.readValue(receipt.outcome(), TransferService.CommandOutcome.class));
    }

    @Transactional(readOnly = true)
    public TransferRepository.Page<TransferRepository.CustodyMovement> custodyTimeline(UUID actor, UUID organizationId,
            UUID transferId, int offset, int limit) {
        var detail = detail(actor, organizationId, transferId);
        return transfers.custodyTimeline(organizationId, detail.transfer().id(), offset, limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.CustodyMovement> custodyTimeline(UUID actor,UUID organizationId,
            UUID transferId,String cursor,int limit) {
        var detail=detail(actor,organizationId,transferId);
        return transfers.custodyTimeline(organizationId,detail.transfer().id(),cursor,limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.Page<TransferRepository.InTransitLine> inTransitAging(UUID actor, UUID organizationId,
            UUID storeId, int offset, int limit) {
        access.requireStore(actor, organizationId, storeId, StoreAction.READ_TRANSFERS, false);
        return transfers.inTransitAging(organizationId, storeId, offset, limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.InTransitLine> inTransitAging(UUID actor,UUID organizationId,
            UUID storeId,String cursor,int limit) {
        access.requireStore(actor,organizationId,storeId,StoreAction.READ_TRANSFERS,false);
        return transfers.inTransitAging(organizationId,storeId,cursor,limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.InTransitLine> transferInTransit(UUID actor,UUID organizationId,
            UUID transferId,String cursor,int limit) {
        var detail=detail(actor,organizationId,transferId);
        return transfers.inTransitAging(organizationId,detail.transfer().id(),cursor,limit,true);
    }

    @Transactional(readOnly = true)
    public TransferRepository.Page<TransferRepository.Discrepancy> discrepancies(UUID actor, UUID organizationId,
            UUID storeId, int offset, int limit) {
        access.requireStore(actor, organizationId, storeId, StoreAction.READ_TRANSFERS, false);
        return transfers.discrepancies(organizationId, storeId, offset, limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.Discrepancy> discrepancies(UUID actor,UUID organizationId,
            UUID storeId,String cursor,int limit) {
        access.requireStore(actor,organizationId,storeId,StoreAction.READ_TRANSFERS,false);
        return transfers.discrepancies(organizationId,storeId,cursor,limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.CursorPage<TransferRepository.Discrepancy> transferDiscrepancies(UUID actor,UUID organizationId,
            UUID transferId,String cursor,int limit) {
        var detail=detail(actor,organizationId,transferId);
        return transfers.discrepancies(organizationId,detail.transfer().id(),cursor,limit,true);
    }

    @Transactional(readOnly = true)
    public List<TransferRepository.ActiveReturn> activeReturns(UUID actor, UUID organizationId, UUID transferId) {
        var detail = detail(actor, organizationId, transferId);
        if (detail.transfer().status().terminal()) return List.of();
        return transfers.activeReturns(organizationId, transferId);
    }

    private void authorizeRead(UUID actor, UUID organizationId, UUID sourceStoreId, UUID destinationStoreId) {
        var membership = access.organization(actor, organizationId);
        try {
            access.requireStore(actor, organizationId, sourceStoreId, StoreAction.READ_TRANSFERS, false);
        } catch (OrganizationUnavailableException unavailable) {
            try {
                access.requireStore(actor, organizationId, destinationStoreId, StoreAction.READ_TRANSFERS, false);
            } catch (OrganizationUnavailableException destinationUnavailable) {
                if (membership.role() == com.stockrescue.access.application.OrganizationAccess.OrganizationRole.ORG_ADMIN
                        && (!access.isActiveStore(organizationId, sourceStoreId)
                                || !access.isActiveStore(organizationId, destinationStoreId))) return;
                throw destinationUnavailable;
            }
        }
    }

    private void authorizeCommandIntentRead(UUID actor, UUID organizationId, String operation, UUID transferId, JsonNode payload) {
        if (operation == null || organizationId == null || actor == null || !TransferService.COMMAND_OPERATIONS.contains(operation)
                || payload == null || !payload.isObject() || (operation.equals("CREATE") != (transferId == null)))
            throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        if (operation.equals("CREATE")) {
            UUID sourceStoreId = payloadUuid(payload, "sourceStoreId");
            access.requireTransferStore(actor, organizationId, sourceStoreId, StoreAction.READ_TRANSFERS, false);
        } else {
            authorizedDetail(actor, organizationId, transferId);
        }
    }

    private TransferDetail authorizedDetail(UUID actor, UUID organizationId, UUID transferId) {
        access.organization(actor, organizationId);
        var detail = transfers.detail(organizationId, transferId).orElseThrow(OrganizationUnavailableException::new);
        authorizeRead(actor, organizationId, detail.transfer().sourceStoreId(), detail.transfer().destinationStoreId());
        return detail;
    }

    private static UUID payloadUuid(JsonNode payload, String field) {
        String value = payload.path(field).asString();
        if (value == null) throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST);
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { throw new InventoryFailure(InventoryFailure.Kind.INVALID_REQUEST); }
    }

    public enum HistoricalSettlementAction { REQUEST_RETURN, CONFIRM_LOSS, OPEN_DISCREPANCY, CLOSE }

    public record TransferCapabilities(List<StoreAction> sourceActions, List<StoreAction> destinationActions,
            boolean canReceivePhysicalSettlement, boolean canConfirmPhysicalReturn,
            List<HistoricalSettlementAction> historicalSettlementActions) {
        public TransferCapabilities {
            sourceActions = List.copyOf(sourceActions);
            destinationActions = List.copyOf(destinationActions);
            historicalSettlementActions = List.copyOf(historicalSettlementActions);
        }
    }
}
