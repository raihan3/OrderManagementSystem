package com.oms.marketdata;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Tracks the current market price of each symbol. This is the reference price used when two
 * {@link com.oms.model.OrderType#MARKET} orders match and neither carries a price of its own, and
 * the trigger source for stop orders.
 *
 * <p>The engine {@link #record(String, BigDecimal) records} every execution; how those recordings
 * become "the market price" is the implementation's pricing policy — see
 * {@link LastTradedPriceTracker} for the standard last-trade policy, with mid-price or VWAP as
 * possible alternatives.
 */
public interface MarketPriceTracker {

    /**
     * Records an execution at the given price for a symbol. May also be called directly to seed an
     * opening/reference price before the symbol has traded.
     */
    void record(String symbol, BigDecimal price);

    /**
     * @return the current market price for the symbol, or empty if none has been established yet
     */
    Optional<BigDecimal> lastPrice(String symbol);
}
