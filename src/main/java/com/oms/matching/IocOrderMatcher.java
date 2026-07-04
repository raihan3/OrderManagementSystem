package com.oms.matching;

import com.oms.book.OrderBook;
import com.oms.marketdata.MarketPriceTracker;
import com.oms.model.MatchResult;
import com.oms.model.Order;
import com.oms.model.OrderStatus;
import com.oms.model.Trade;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Matches an immediate-or-cancel (IOC) order. It crosses on price exactly like a limit order, but
 * never rests: any quantity that cannot be filled immediately is cancelled rather than placed on
 * the book.
 */
final class IocOrderMatcher extends LimitOrderMatcher {

    IocOrderMatcher(Clock clock, MarketPriceTracker marketPriceTracker) {
        super(clock, marketPriceTracker);
    }

    @Override
    protected MatchResult handleRemainder(
            Order incoming, BigDecimal remaining, List<Trade> trades, OrderBook book) {
        Order cancelled = incoming.toBuilder()
                .quantity(remaining)
                .status(OrderStatus.CANCELLED)
                .build();
        return new MatchResult(trades, Optional.of(cancelled));
    }
}
