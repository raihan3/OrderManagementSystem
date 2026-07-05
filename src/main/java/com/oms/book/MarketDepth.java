package com.oms.book;

import com.oms.model.Order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The standard "market depth" (level-2) view: the top-N bid and ask price levels, each aggregated
 * across the resting orders at that price. Bids are ordered highest price first, asks lowest
 * first — i.e. best level first on both sides.
 *
 * <p>Resting market orders are excluded: they carry no price and so cannot form a price level
 * (a depth consumer sees only the priced liquidity ladder).
 *
 * @param bids the aggregated buy levels, best (highest) first, at most N entries
 * @param asks the aggregated sell levels, best (lowest) first, at most N entries
 */
public record MarketDepth(List<PriceLevel> bids, List<PriceLevel> asks) {

    public MarketDepth {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }

    /**
     * Builds a depth view from the two sides of a book. Each collection must already be in the
     * book's priority order (as {@link OrderBook#buyOrders()} / {@link OrderBook#sellOrders()} and
     * the snapshot lists are), because the aggregation relies on orders at the same price being
     * adjacent.
     *
     * @param bidsInPriorityOrder resting buy orders, best first
     * @param asksInPriorityOrder resting sell orders, best first
     * @param maxLevels           the N in top-N: at most this many levels per side
     */
    public static MarketDepth of(Collection<Order> bidsInPriorityOrder,
                                 Collection<Order> asksInPriorityOrder,
                                 int maxLevels) {
        Objects.requireNonNull(bidsInPriorityOrder, "bids must not be null");
        Objects.requireNonNull(asksInPriorityOrder, "asks must not be null");
        if (maxLevels < 1) {
            throw new IllegalArgumentException("maxLevels must be at least 1, but was " + maxLevels);
        }
        return new MarketDepth(
                aggregate(bidsInPriorityOrder, maxLevels),
                aggregate(asksInPriorityOrder, maxLevels));
    }

    /**
     * Single pass over the side in priority order, rolling adjacent same-priced orders into one
     * level and stopping once {@code maxLevels} levels are complete — only the orders inside the
     * top N levels are ever visited. Prices compare via {@code compareTo}, so scale variants of
     * the same price ({@code 100} vs {@code 100.00}) fall into one level.
     */
    private static List<PriceLevel> aggregate(Collection<Order> ordersInPriorityOrder, int maxLevels) {
        List<PriceLevel> levels = new ArrayList<>();
        BigDecimal levelPrice = null;
        BigDecimal levelQuantity = null;
        int levelOrders = 0;

        for (Order resting : ordersInPriorityOrder) {
            if (resting.isMarket()) {
                continue; // no price, no level
            }
            if (levelPrice != null && levelPrice.compareTo(resting.price()) == 0) {
                levelQuantity = levelQuantity.add(resting.quantity());
                levelOrders++;
                continue;
            }
            if (levelPrice != null) {
                levels.add(new PriceLevel(levelPrice, levelQuantity, levelOrders));
                if (levels.size() == maxLevels) {
                    return List.copyOf(levels);
                }
            }
            levelPrice = resting.price();
            levelQuantity = resting.quantity();
            levelOrders = 1;
        }

        if (levelPrice != null) {
            levels.add(new PriceLevel(levelPrice, levelQuantity, levelOrders));
        }
        return List.copyOf(levels);
    }
}
