package com.stockrescue.inventory.domain;

/** Disjoint EA buckets. Availability is derived, never independently writable. */
public record StockBuckets(long onHand, long reserved, long quarantined, long otherBlocked) {
    public static final StockBuckets ZERO = new StockBuckets(0, 0, 0, 0);

    public StockBuckets {
        if (onHand < 0 || reserved < 0 || quarantined < 0 || otherBlocked < 0
                || reserved > onHand || quarantined > onHand-reserved || otherBlocked > onHand-reserved-quarantined) {
            throw new IllegalArgumentException("Stock buckets must be nonnegative and disjoint within on-hand stock");
        }
    }

    public long available() { return onHand-reserved-quarantined-otherBlocked; }

    public StockBuckets apply(Delta delta) {
        return new StockBuckets(Math.addExact(onHand, delta.onHand()), Math.addExact(reserved, delta.reserved()),
                Math.addExact(quarantined, delta.quarantined()), Math.addExact(otherBlocked, delta.otherBlocked()));
    }

    public record Delta(long onHand, long reserved, long quarantined, long otherBlocked) {
        public Delta {
            if (onHand==Long.MIN_VALUE || reserved==Long.MIN_VALUE || quarantined==Long.MIN_VALUE || otherBlocked==Long.MIN_VALUE) {
                throw new IllegalArgumentException("Stock delta exceeds the EA quantity range");
            }
        }
    }
}
