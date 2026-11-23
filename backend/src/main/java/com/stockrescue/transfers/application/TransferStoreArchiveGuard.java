package com.stockrescue.transfers.application;

import java.util.UUID;
import com.stockrescue.stores.application.StoreArchiveGuard;
import com.stockrescue.transfers.persistence.TransferRepository;
import org.springframework.stereotype.Service;

@Service
public class TransferStoreArchiveGuard implements StoreArchiveGuard {
    private final TransferRepository transfers;

    public TransferStoreArchiveGuard(TransferRepository transfers) {
        this.transfers = transfers;
    }

    @Override
    public boolean hasOpenTransfer(UUID organizationId, UUID storeId) {
        return transfers.hasOpenTransfer(organizationId, storeId);
    }
}
