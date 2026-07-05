package com.oms.marketdata;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The standard {@link MarketPriceTracker} policy: a symbol's market price is its last-traded
 * price. Every recorded execution replaces the previous value, so the tracked price always
 * reflects the most recent trade. A symbol has no market price until it has traded at least once
 * (or one is seeded via {@link #record(String, BigDecimal)}).
 */
public class LastTradedPriceTracker implements MarketPriceTracker {

    private final Map<String, BigDecimal> lastPriceBySymbol = new HashMap<>();

    @Override
    public void record(String symbol, BigDecimal price) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(price, "price must not be null");
        lastPriceBySymbol.put(symbol, price);
    }

    @Override
    public Optional<BigDecimal> lastPrice(String symbol) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        return Optional.ofNullable(lastPriceBySymbol.get(symbol));
    }
}
