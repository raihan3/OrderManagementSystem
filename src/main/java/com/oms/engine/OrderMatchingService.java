package com.oms.engine;

import com.oms.book.OrderBook;
import com.oms.marketdata.MarketPriceTracker;
import com.oms.matching.OrderMatcher;
import com.oms.matching.OrderMatcherFactory;
import com.oms.model.MatchResult;
import com.oms.model.Order;

import java.time.Clock;
import java.util.Objects;

/**
 * Continuous price-time-priority matching engine for a single instrument.
 *
 * <p>Each incoming order is matched against the opposite side of the {@link OrderBook} by the
 * {@link OrderMatcher} that {@link OrderMatcherFactory} selects for the order's type. Executions
 * are priced at the resting (maker) order's price; whatever quantity cannot be filled rests on the
 * book, per the chosen matcher.
 */
public class OrderMatchingService {

    private final OrderBook orderBook;
    private final OrderMatcherFactory matcherFactory;

    public OrderMatchingService() {
        this(new OrderBook(), Clock.systemUTC());
    }

    public OrderMatchingService(OrderBook orderBook, Clock clock) {
        this(orderBook, new OrderMatcherFactory(clock));
    }

    public OrderMatchingService(OrderBook orderBook, OrderMatcherFactory matcherFactory) {
        this.orderBook = Objects.requireNonNull(orderBook, "orderBook must not be null");
        this.matcherFactory = Objects.requireNonNull(matcherFactory, "matcherFactory must not be null");
    }

    /**
     * Matches an incoming order against the book, delegating to the matcher selected for its type.
     *
     * @param incoming the order to match; must not already be resting on the book
     * @return the executions produced and any unfilled remainder
     */
    public MatchResult match(Order incoming) {
        Objects.requireNonNull(incoming, "incoming order must not be null");
        OrderMatcher matcher = matcherFactory.matcherFor(incoming);
        return matcher.match(incoming, orderBook);
    }

    /**
     * @return the resting order book this service matches against
     */
    public OrderBook orderBook() {
        return orderBook;
    }

    /**
     * @return the tracker of each symbol's market (last-traded) price; use it to seed an opening
     *         price or read the current market price
     */
    public MarketPriceTracker marketPriceTracker() {
        return matcherFactory.marketPriceTracker();
    }
}
