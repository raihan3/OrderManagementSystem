package com.oms.book;

/** Runs the {@link MarketDepthContractTest} against the TreeMap-backed book. */
class PriceTimePriorityOrderBookDepthTest extends MarketDepthContractTest {

    @Override
    protected OrderBook createBook() {
        return new PriceTimePriorityOrderBook();
    }
}
