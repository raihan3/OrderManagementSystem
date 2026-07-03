package com.oms.matching;

import com.oms.book.OrderBook;
import com.oms.model.Order;
import com.oms.model.OrderStatus;
import com.oms.model.Side;
import com.oms.model.Trade;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Continuous price-time-priority matching engine for a single instrument.
 *
 * <p>Each incoming order is matched against the opposite side of the {@link OrderBook}, taking the
 * best-priced resting order first and walking outward while prices still cross. Every execution is
 * priced at the resting (maker) order's price, so an aggressive order receives any price
 * improvement. Whatever quantity remains after the book is exhausted or prices no longer cross is
 * placed on the book as a resting order.
 */
public class OrderMatchingService {

    private final OrderBook orderBook;
    private final Clock clock;

    public OrderMatchingService() {
        this(new OrderBook(), Clock.systemUTC());
    }

    public OrderMatchingService(OrderBook orderBook, Clock clock) {
        this.orderBook = Objects.requireNonNull(orderBook, "orderBook must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Matches an incoming order against the book, executing trades in price-time priority and
     * resting any unfilled remainder.
     *
     * @param incoming the order to match; must not already be resting on the book
     * @return the executions produced and the resting remainder, if any
     */
    public MatchResult match(Order incoming) {
        Objects.requireNonNull(incoming, "incoming order must not be null");

        List<Trade> trades = new ArrayList<>();
        BigDecimal remaining = incoming.quantity();

        while (remaining.signum() > 0) {
            Optional<Order> bestOpposite = bestOpposite(incoming.side());
            if (bestOpposite.isEmpty() || !isCrossingAskingPrice(incoming, bestOpposite.get())) {
                break;
            }

            Order resting = bestOpposite.get();
            BigDecimal fillQty = remaining.min(resting.quantity());
            trades.add(execute(incoming, resting, fillQty));

            BigDecimal restingRemaining = resting.quantity().subtract(fillQty);
            if (restingRemaining.signum() == 0) {
                orderBook.removeOrder(resting);
            } else {
                // Reduce the resting order in place; its timestamp is preserved, so it keeps its
                // time priority for subsequent matches.
                orderBook.updateOrder(resting.id(), b -> b.quantity(restingRemaining));
            }

            remaining = remaining.subtract(fillQty);
        }

        if (remaining.signum() == 0) {
            return new MatchResult(trades, Optional.empty());
        }

        Order rested = incoming.toBuilder()
                .quantity(remaining)
                .status(OrderStatus.PENDING)
                .build();
        orderBook.addOrder(rested);
        return new MatchResult(trades, Optional.of(rested));
    }

    /**
     * @return the resting order book this service matches against
     */
    public OrderBook orderBook() {
        return orderBook;
    }

    private Optional<Order> bestOpposite(Side incomingSide) {
        return incomingSide == Side.BUY ? orderBook.bestSell() : orderBook.bestBuy();
    }

    /**
     * @return whether the incoming order's price crosses the resting order's price
     */
    private boolean isCrossingAskingPrice(Order incoming, Order resting) {
        return incoming.side() == Side.BUY
                ? incoming.price().compareTo(resting.price()) >= 0   // buy must pay at least the ask
                : incoming.price().compareTo(resting.price()) <= 0;  // sell must accept at most the bid
    }

    private Trade execute(Order incoming, Order resting, BigDecimal quantity) {
        boolean incomingIsBuy = incoming.side() == Side.BUY;
        return new Trade(
                incomingIsBuy ? incoming.id() : resting.id(),
                incomingIsBuy ? resting.id() : incoming.id(),
                incoming.symbol(),
                resting.price(),
                quantity,
                clock.instant()
        );
    }
}
