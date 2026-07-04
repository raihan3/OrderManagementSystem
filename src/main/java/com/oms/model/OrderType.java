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
 * </ul>
 */
public enum OrderType {
    LIMIT,
    MARKET,
    IOC
}
