package com.stockrescue.inventory.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockBucketsTest {
    @Test void reservationAndHoldChangesNeverCreatePhysicalUnits() {
        var stock=new StockBuckets(10,0,0,0).apply(new StockBuckets.Delta(0,2,3,1));
        assertThat(stock.onHand()).isEqualTo(10);
        assertThat(stock.available()).isEqualTo(4);
        assertThat(stock.apply(new StockBuckets.Delta(0,-1,0,0)).available()).isEqualTo(5);
    }
    @Test void disjointBucketsCannotOversubscribeOnHandEvenAtTheIntegerLimit() {
        assertThatThrownBy(()->new StockBuckets(Long.MAX_VALUE,Long.MAX_VALUE,1,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->StockBuckets.ZERO.apply(new StockBuckets.Delta(-1,0,0,0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new StockBuckets(1,1,0,0).apply(new StockBuckets.Delta(-1,0,0,0))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void quantityOverflowAndUnrepresentableCompensationFailExplicitly() {
        assertThatThrownBy(()->new StockBuckets(Long.MAX_VALUE,0,0,0).apply(new StockBuckets.Delta(1,0,0,0))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(()->new StockBuckets.Delta(Long.MIN_VALUE,0,0,0)).isInstanceOf(IllegalArgumentException.class);
    }
}
