package com.oms.matching;

import com.oms.marketdata.MarketPriceTracker;
import com.oms.model.Order;
import com.oms.model.Side;

import java.time.Clock;

/**
 * Matches a limit order. It crosses a resting market order unconditionally (that order accepts any
 * price) and a resting limit order only when its price is at least as aggressive as the resting
 * price. Any unfilled quantity rests on the book (via the inherited default).
 *
 * <p>{@link IocOrderMatcher} extends this to reuse the same price-based crossing rule while
 * cancelling, rather than resting, the unfilled remainder.
 */
class LimitOrderMatcher extends AbstractOrderMatcher {

    LimitOrderMatcher(Clock clock, MarketPriceTracker marketPriceTracker) {
        super(clock, marketPriceTracker);
    }

    @Override
    protected boolean crosses(Order incoming, Order resting) {
        if (resting.isMarket()) {
            return true; // a resting market order trades at any price
        }
        return incoming.side() == Side.BUY
                ? incoming.price().compareTo(resting.price()) >= 0   // buy must pay at least the ask
                : incoming.price().compareTo(resting.price()) <= 0;  // sell must accept at most the bid
    }
}
