package com.oms.matching;

import com.oms.book.OrderBook;
import com.oms.marketdata.MarketPriceTracker;
import com.oms.model.Order;
import com.oms.model.Side;

import java.time.Clock;

/**
 * Matches a fill-or-kill (FOK) order: either the entire quantity executes immediately or nothing
 * does.
 *
 * <p>Before any fill occurs, {@link #canExecute(Order, OrderBook)} measures the opposite side's
 * {@link OrderBook#availableLiquidity(Side, java.math.BigDecimal) executable liquidity} at the
 * order's limit price. Only if that covers the full quantity does the inherited fill loop run — and
 * because the check passed, the loop is then guaranteed to fill completely. Otherwise the book is
 * left untouched and the whole order is cancelled (the remainder policy inherited from
 * {@link IocOrderMatcher}), producing zero trades.
 */
final class FokOrderMatcher extends IocOrderMatcher {

    FokOrderMatcher(Clock clock, MarketPriceTracker marketPriceTracker) {
        super(clock, marketPriceTracker);
    }

    @Override
    protected boolean canExecute(Order incoming, OrderBook book) {
        Side oppositeSide = incoming.side() == Side.BUY ? Side.SELL : Side.BUY;
        return book.availableLiquidity(oppositeSide, incoming.price())
                .compareTo(incoming.quantity()) >= 0;
    }
}
