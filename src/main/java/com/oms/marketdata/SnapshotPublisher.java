package com.oms.marketdata;

import com.oms.book.OrderBookSnapshot;

/**
 * Receives every {@link OrderBookSnapshot} the {@link OrderBookSnapshotService} captures — the
 * seam where snapshots leave the engine for a market-data feed, a persistent store, a log, etc.
 *
 * <p>Invoked on the matching thread, so implementations should hand off expensive work rather
 * than block order processing.
 */
@FunctionalInterface
public interface SnapshotPublisher {

    void publish(OrderBookSnapshot snapshot);
}
