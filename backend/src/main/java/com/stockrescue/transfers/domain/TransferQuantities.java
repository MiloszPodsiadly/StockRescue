package com.stockrescue.transfers.domain;

/**
 * Per-line EA conservation. Transit is accounted separately from store stock.
 */
public record TransferQuantities(long dispatched, long physicallyReceived, long confirmedLoss,
        long physicallyReturned, long outstanding) {

    public TransferQuantities {
        if (dispatched < 0 || physicallyReceived < 0 || confirmedLoss < 0
                || physicallyReturned < 0 || outstanding < 0) {
            throw new IllegalArgumentException("Transfer quantities must be nonnegative EA integers");
        }
        long settled = Math.addExact(Math.addExact(physicallyReceived, confirmedLoss), physicallyReturned);
        if (Math.addExact(settled, outstanding) != dispatched) {
            throw new IllegalArgumentException("Dispatched quantity must equal settled and outstanding quantity");
        }
    }

    public static TransferQuantities undispatched() {
        return new TransferQuantities(0, 0, 0, 0, 0);
    }

    public TransferQuantities dispatch(long quantity) {
        requirePositive(quantity);
        if (dispatched != 0) {
            throw new IllegalStateException("A transfer line can be dispatched only once");
        }
        return new TransferQuantities(quantity, 0, 0, 0, quantity);
    }

    public TransferQuantities receive(long quantity) {
        requireOutstanding(quantity);
        return new TransferQuantities(dispatched, Math.addExact(physicallyReceived, quantity),
                confirmedLoss, physicallyReturned, outstanding - quantity);
    }

    public TransferQuantities confirmLoss(long quantity) {
        requireOutstanding(quantity);
        return new TransferQuantities(dispatched, physicallyReceived, Math.addExact(confirmedLoss, quantity),
                physicallyReturned, outstanding - quantity);
    }

    public TransferQuantities confirmReturn(long quantity) {
        requireOutstanding(quantity);
        return new TransferQuantities(dispatched, physicallyReceived, confirmedLoss,
                Math.addExact(physicallyReturned, quantity), outstanding - quantity);
    }

    private void requireOutstanding(long quantity) {
        requirePositive(quantity);
        if (dispatched == 0 || quantity > outstanding) {
            throw new IllegalArgumentException("Settlement quantity exceeds dispatched outstanding stock");
        }
    }

    private static void requirePositive(long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Transfer quantity must be positive");
        }
    }
}
