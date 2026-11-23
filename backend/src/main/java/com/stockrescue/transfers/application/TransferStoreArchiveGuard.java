package com.stockrescue.transfers.application;

import java.util.UUID;
import com.stockrescue.stores.application.StoreArchiveGuard;
import com.stockrescue.stores.application.TransferCustodyArchiveGuard;
import com.stockrescue.transfers.persistence.TransferRepository;
import org.springframework.stereotype.Service;

@Service
public class TransferStoreArchiveGuard implements StoreArchiveGuard, TransferCustodyArchiveGuard {
    private final TransferRepository transfers;

    public TransferStoreArchiveGuard(TransferRepository transfers) {
        this.transfers = transfers;
    }

    @Override
    public boolean hasOpenTransfer(UUID organizationId, UUID storeId) {
        return transfers.hasOpenTransfer(organizationId, storeId);
    }

    @Override
    public boolean hasOpenTransferForProduct(UUID organizationId, UUID productId) {
        return transfers.hasOpenTransferForProduct(organizationId, productId);
    }

    @Override
    public boolean hasOpenTransferForLocation(UUID organizationId, UUID storeId, UUID locationId) {
        return transfers.hasOpenTransferForLocation(organizationId, storeId, locationId);
    }
}
