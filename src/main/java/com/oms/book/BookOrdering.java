package com.oms.book;

import com.oms.model.Order;

import java.util.Comparator;

/**
 * The canonical order-priority comparators, shared by every {@link OrderBook} implementation so
 * that priority semantics cannot drift between storage strategies.
 */
final class BookOrdering {

    private BookOrdering() {
    }

    /**
     * Resting-book priority: market orders first (time-ordered among themselves), then limit
     * orders by price (highest bid / lowest ask first) then time; id breaks remaining ties so two
     * distinct orders never compare as equal.
     */
    static Comparator<Order> restingOrder(boolean buySide) {
        Comparator<Order> marketBeforeLimit = Comparator.comparing((Order o) -> o.isMarket() ? 0 : 1);
        Comparator<Order> byTime = Comparator.comparing(Order::timestamp).thenComparing(Order::id);
        Comparator<Order> byPrice = buySide
                ? Comparator.comparing(Order::price).reversed()  // highest bid first
                : Comparator.comparing(Order::price);            // lowest ask first
        Comparator<Order> byPriceThenTime = byPrice.thenComparing(byTime);

        // Once market-vs-limit ties, both orders are the same kind: market orders compare on time
        // only (they have no price), limit orders on price then time.
        Comparator<Order> withinGroup = (a, b) ->
                a.isMarket() ? byTime.compare(a, b) : byPriceThenTime.compare(a, b);

        return marketBeforeLimit.thenComparing(withinGroup);
    }

    /**
     * Stop-list priority by trigger proximity: buy stops fire as the market rises, so the lowest
     * stop price triggers first; sell stops fire as the market falls, so the highest stop price
     * triggers first. Ties break on time then id, mirroring the resting book.
     */
    static Comparator<Order> stopOrder(boolean buySide) {
        Comparator<Order> byTriggerProximity = buySide
                ? Comparator.comparing(Order::stopPrice)
                : Comparator.comparing(Order::stopPrice).reversed();
        return byTriggerProximity.thenComparing(Order::timestamp).thenComparing(Order::id);
    }
}
