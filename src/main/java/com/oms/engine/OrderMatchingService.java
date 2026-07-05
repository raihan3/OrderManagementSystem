package com.oms.engine;

import com.oms.book.InMemoryOrderBookRegistry;
import com.oms.book.OrderBook;
import com.oms.book.OrderBookRegistry;
import com.oms.marketdata.MarketPriceTracker;
import com.oms.marketdata.OrderBookSnapshotService;
import com.oms.matching.OrderMatcher;
import com.oms.matching.OrderMatcherFactory;
import com.oms.model.MatchResult;
import com.oms.model.Order;
import com.oms.model.OrderStatus;
import com.oms.model.OrderType;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Continuous price-time-priority matching engine across multiple instruments.
 *
 * <p>Each incoming order is routed by its {@link Order#symbol() symbol} to that symbol's
 * {@link OrderBook} (via the {@link OrderBookRegistry}), then matched against the opposite side of
 * that book by the {@link OrderMatcher} selected for the order's type. Because books are per-symbol,
 * orders for different symbols never cross. Executions are priced at the resting (maker) order's
 * price; whatever quantity cannot be filled rests on the book, per the chosen matcher.
 *
 * <p>Trigger-based ({@link OrderType#STOP} and {@link OrderType#STOP_LIMIT}) orders are not matched
 * on arrival: they are parked in the book's stop lists (unless the market has already traded at or
 * through their trigger, in which case they activate immediately). After every match, the engine
 * polls the stop lists with the symbol's last-traded price and activates whatever fired — a
 * {@code STOP} as a {@link OrderType#MARKET} order, a {@code STOP_LIMIT} as a {@link OrderType#LIMIT}
 * order at its limit price — re-matching until no further stops trigger, so activations that move
 * the price cascade into further activations.
 */
public class OrderMatchingService implements MatchingEngine {

    private final OrderBookRegistry orderBooks;
    private final OrderMatcherFactory matcherFactory;
    private final Clock clock;
    private final OrderBookSnapshotService snapshotService; // null when snapshotting is disabled

    public OrderMatchingService() {
        this(new InMemoryOrderBookRegistry(), Clock.systemUTC());
    }

    public OrderMatchingService(OrderBookRegistry orderBooks, Clock clock) {
        this(orderBooks, new OrderMatcherFactory(clock), clock, null);
    }

    /**
     * Creates an engine that ticks the given snapshot service after every processed order, so
     * periodic snapshots are taken in-band on the matching thread.
     */
    public OrderMatchingService(OrderBookRegistry orderBooks, Clock clock,
                                OrderBookSnapshotService snapshotService) {
        this(orderBooks, new OrderMatcherFactory(clock), clock,
                Objects.requireNonNull(snapshotService, "snapshotService must not be null"));
    }

    public OrderMatchingService(OrderBookRegistry orderBooks, OrderMatcherFactory matcherFactory, Clock clock,
                                OrderBookSnapshotService snapshotService) {
        this.orderBooks = Objects.requireNonNull(orderBooks, "orderBooks must not be null");
        this.matcherFactory = Objects.requireNonNull(matcherFactory, "matcherFactory must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.snapshotService = snapshotService;
    }

    /**
     * Matches an incoming order against the book for its symbol, delegating to the matcher selected
     * for its type. A stop order is instead parked in the stop list (or activated immediately if the
     * market already trades at or through its trigger); its {@link MatchResult} then carries the
     * parked stop as the remainder with no trades. Any trades produced here may trigger parked
     * stops, whose activations are matched before this method returns; trades from those
     * activations belong to the activated orders and are not folded into the returned result.
     *
     * @param incoming the order to match; must not already be resting on the book
     * @return the executions produced and any unfilled remainder
     */
    @Override
    public MatchResult match(Order incoming) {
        Objects.requireNonNull(incoming, "incoming order must not be null");
        OrderBook book = orderBooks.bookFor(incoming.symbol());

        MatchResult result = incoming.isStop()
                ? parkOrActivateStop(incoming, book)
                : matcherFactory.matcherFor(incoming).match(incoming, book);

        activateTriggeredStops(book, incoming.symbol());
        if (snapshotService != null) {
            // In-band periodic snapshotting: runs on this matching thread, once the order (and any
            // stop cascade it caused) has fully settled, so a snapshot never sees a half-processed
            // book.
            snapshotService.tick();
        }
        return result;
    }

    /**
     * Parks a newly arrived stop order, unless the market has already traded at or through its
     * trigger — then it skips the stop list and activates on the spot.
     */
    private MatchResult parkOrActivateStop(Order stop, OrderBook book) {
        Optional<BigDecimal> lastPrice = marketPriceTracker().lastPrice(stop.symbol());
        if (lastPrice.isPresent() && stop.isStopTriggeredAt(lastPrice.get())) {
            Order activated = activate(stop);
            return matcherFactory.matcherFor(activated).match(activated, book);
        }
        book.addStopOrder(stop);
        return new MatchResult(List.of(), Optional.of(stop));
    }

    /**
     * Drains and activates every stop fired by the symbol's last-traded price, re-polling after
     * each batch because activations can trade at new prices and fire further stops. Terminates
     * because each pass permanently removes at least one stop from the finite stop lists.
     */
    private void activateTriggeredStops(OrderBook book, String symbol) {
        while (true) {
            Optional<BigDecimal> lastPrice = marketPriceTracker().lastPrice(symbol);
            if (lastPrice.isEmpty()) {
                return;
            }
            List<Order> triggered = book.pollTriggeredStops(lastPrice.get());
            if (triggered.isEmpty()) {
                return;
            }
            for (Order stop : triggered) {
                Order activated = activate(stop);
                matcherFactory.matcherFor(activated).match(activated, book);
            }
        }
    }

    /**
     * Converts a fired stop into the live order it becomes: a {@link OrderType#STOP} into a
     * {@link OrderType#MARKET} order, a {@link OrderType#STOP_LIMIT} into a {@link OrderType#LIMIT}
     * order at its limit price. The id is preserved; the timestamp is re-stamped to the activation
     * instant, since the order only now joins the queue (a dormant stop earns no time priority).
     */
    private Order activate(Order stop) {
        Order.Builder activated = stop.toBuilder()
                .stopPrice(null)
                .timestamp(clock.instant())
                .status(OrderStatus.PENDING);
        return switch (stop.type()) {
            case STOP -> activated.type(OrderType.MARKET).price(null).build();
            case STOP_LIMIT -> activated.type(OrderType.LIMIT).build();
            default -> throw new IllegalArgumentException(
                    "order " + stop.id() + " is a " + stop.type() + " order, not an activatable stop");
        };
    }

    /**
     * @return the registry of per-symbol order books this service matches against
     */
    public OrderBookRegistry orderBooks() {
        return orderBooks;
    }

    /**
     * @return the order book for the given symbol (subject to the registry's create-on-demand or
     *         strict policy)
     */
    public OrderBook orderBook(String symbol) {
        return orderBooks.bookFor(symbol);
    }

    /**
     * @return the tracker of each symbol's market (last-traded) price; use it to seed an opening
     *         price or read the current market price
     */
    public MarketPriceTracker marketPriceTracker() {
        return matcherFactory.marketPriceTracker();
    }
}
