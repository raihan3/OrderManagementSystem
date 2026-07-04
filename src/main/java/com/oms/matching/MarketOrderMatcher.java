package com.oms.matching;

import com.oms.marketdata.MarketPriceTracker;
import com.oms.model.Order;

import java.time.Clock;

/**
 * Matches a market order. It crosses any resting limit order regardless of price. It can also cross
 * a resting market order, but only when the symbol has a tracked {@link MarketPriceTracker market
 * price} to execute at — until the symbol has traded at least once there is no reference price, so
 * the incoming order rests instead (via the inherited default) at the top of the book. Any unfilled
 * quantity likewise rests, ahead of the limit orders on its side.
 */
final class MarketOrderMatcher extends AbstractOrderMatcher {

    MarketOrderMatcher(Clock clock, MarketPriceTracker marketPriceTracker) {
        super(clock, marketPriceTracker);
    }

    @Override
    protected boolean crosses(Order incoming, Order resting) {
        if (resting.isMarket()) {
            // Two market orders can only trade if a reference price exists for the symbol.
            return marketPrice(incoming.symbol()).isPresent();
        }
        return true;
    }
}
