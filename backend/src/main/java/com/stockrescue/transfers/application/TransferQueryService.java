package com.stockrescue.transfers.application;

import java.util.List;
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
    public TransferRepository.Page<TransferRepository.InTransitLine> inTransitAging(UUID actor, UUID organizationId,
            UUID storeId, int offset, int limit) {
        access.requireStore(actor, organizationId, storeId, StoreAction.READ_TRANSFERS, false);
        return transfers.inTransitAging(organizationId, storeId, offset, limit);
    }

    @Transactional(readOnly = true)
    public TransferRepository.Page<TransferRepository.Discrepancy> discrepancies(UUID actor, UUID organizationId,
            UUID storeId, int offset, int limit) {
        access.requireStore(actor, organizationId, storeId, StoreAction.READ_TRANSFERS, false);
        return transfers.discrepancies(organizationId, storeId, offset, limit);
    }

    @Transactional(readOnly = true)
    public List<TransferRepository.ActiveReturn> activeReturns(UUID actor, UUID organizationId, UUID transferId) {
        var detail = detail(actor, organizationId, transferId);
        if (detail.transfer().status().terminal()) return List.of();
        return transfers.activeReturns(organizationId, transferId);
    }

    private void authorizeRead(UUID actor, UUID organizationId, UUID sourceStoreId, UUID destinationStoreId) {
        access.organization(actor, organizationId);
        try {
            access.requireStore(actor, organizationId, sourceStoreId, StoreAction.READ_TRANSFERS, false);
        } catch (OrganizationUnavailableException unavailable) {
            access.requireStore(actor, organizationId, destinationStoreId, StoreAction.READ_TRANSFERS, false);
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
}
