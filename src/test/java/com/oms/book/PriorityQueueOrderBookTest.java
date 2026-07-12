package com.oms.book;

/** Runs the {@link OrderBookContractTest} against the heap-backed, lazy-deletion book. */
class PriorityQueueOrderBookTest extends OrderBookContractTest {

    @Override
    protected OrderBook createBook() {
        return new PriorityQueueOrderBook();
    }
}
