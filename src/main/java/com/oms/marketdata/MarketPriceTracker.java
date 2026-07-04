package com.oms.marketdata;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Tracks the current market price of each symbol as its last-traded price.
 *
 * <p>Every execution updates the symbol's price via {@link #record(String, BigDecimal)}, so the
 * tracked value always reflects the most recent trade. This is the reference price used when two
 * {@link com.oms.model.OrderType#MARKET} orders match and neither carries a price of its own. A
 * symbol has no market price until it has traded at least once (or one is seeded via
 * {@link #record(String, BigDecimal)}).
 */
public class MarketPriceTracker {

    private final Map<String, BigDecimal> lastPriceBySymbol = new HashMap<>();

    /**
     * Records the latest traded price for a symbol, becoming its current market price.
     */
    public void record(String symbol, BigDecimal price) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(price, "price must not be null");
        lastPriceBySymbol.put(symbol, price);
    }

    /**
     * @return the current market (last-traded) price for the symbol, or empty if it has never traded
     */
    public Optional<BigDecimal> lastPrice(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        return Optional.ofNullable(lastPriceBySymbol.get(symbol));
    }
}
