package com.oms.matching;

import com.oms.marketdata.MarketPriceTracker;
import com.oms.model.Order;

import java.time.Clock;
import java.util.Objects;

/**
 * Supplies the {@link OrderMatcher} appropriate to an order's {@link com.oms.model.OrderType}: a
 * limit-, market-, or immediate-or-cancel matcher.
 *
 * <p>The matchers are stateless and thread-safe, so a single instance of each is created up front
 * and reused for every order. They share one {@link MarketPriceTracker} so that trades from any of
 * them keep the symbol's market price current for pricing market-versus-market matches.
 */
public class OrderMatcherFactory {

    private final MarketPriceTracker marketPriceTracker;
    private final OrderMatcher limitOrderMatcher;
    private final OrderMatcher marketOrderMatcher;
    private final OrderMatcher iocOrderMatcher;

    public OrderMatcherFactory(Clock clock) {
        this(clock, new MarketPriceTracker());
    }

    public OrderMatcherFactory(Clock clock, MarketPriceTracker marketPriceTracker) {
        Objects.requireNonNull(clock, "clock must not be null");
        this.marketPriceTracker = Objects.requireNonNull(marketPriceTracker, "marketPriceTracker must not be null");
        this.limitOrderMatcher = new LimitOrderMatcher(clock, marketPriceTracker);
        this.marketOrderMatcher = new MarketOrderMatcher(clock, marketPriceTracker);
        this.iocOrderMatcher = new IocOrderMatcher(clock, marketPriceTracker);
    }

    /**
     * @return the matcher that implements the matching rules for the given order's type
     */
    public OrderMatcher matcherFor(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        return switch (order.type()) {
            case LIMIT -> limitOrderMatcher;
            case MARKET -> marketOrderMatcher;
            case IOC -> iocOrderMatcher;
        };
    }

    /**
     * @return the shared tracker of each symbol's market price; expose it to seed an opening price
     *         or to read the current market price
     */
    public MarketPriceTracker marketPriceTracker() {
        return marketPriceTracker;
    }
}
