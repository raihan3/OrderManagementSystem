package com.oms.model;

/**
 * The pricing and time-in-force behaviour of an order.
 *
 * <ul>
 *   <li>{@link #LIMIT} — carries a price and only executes at that price or better; any unfilled
 *       quantity rests on the book.</li>
 *   <li>{@link #MARKET} — carries no price and executes immediately against the best available
 *       opposite orders, whatever their price.</li>
 *   <li>{@link #IOC} — an immediate-or-cancel order: like a limit order it carries a price and only
 *       executes at that price or better, but it never rests — any quantity that cannot be filled
 *       immediately is cancelled.</li>
 *   <li>{@link #FOK} — a fill-or-kill order: like an IOC it carries a price and never rests, but it
 *       also forbids partial fills — either the entire quantity executes immediately or the whole
 *       order is cancelled without trading.</li>
 *   <li>{@link #STOP} — a trigger-based order carrying a stop price but no limit price. It lies
 *       dormant in the stop book until the market trades at or through its stop price, then
 *       activates as a {@link #MARKET} order.</li>
 *   <li>{@link #STOP_LIMIT} — a trigger-based order carrying both a stop price and a limit price.
 *       It lies dormant until triggered, then activates as a {@link #LIMIT} order at its limit
 *       price.</li>
 * </ul>
 */
public enum OrderType {
    LIMIT,
    MARKET,
    IOC,
    FOK,
    STOP,
    STOP_LIMIT
}
