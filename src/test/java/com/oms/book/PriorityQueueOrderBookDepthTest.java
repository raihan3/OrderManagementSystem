package com.oms.book;

/** Runs the {@link MarketDepthContractTest} against the heap-backed, lazy-deletion book. */
class PriorityQueueOrderBookDepthTest extends MarketDepthContractTest {

    @Override
    protected OrderBook createBook() {
        return new PriorityQueueOrderBook();
    }
}
