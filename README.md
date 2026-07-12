# Order Management System

A single-process order matching engine in Java, built around an immutable domain model and a
price-time-priority order book. It supports six order types (limit, market, IOC, FOK, stop,
stop-limit), multi-symbol routing, market-data views (spread, liquidity, depth, snapshots), and a
strategy-based matching core that makes new order types a small, self-contained extension.

## Requirements & build

- Java 25, Gradle (wrapper included), JUnit 5.

```bash
./gradlew build   # compile + run the full test suite
./gradlew test    # tests only
```

## Architecture

```
com.oms
├── model        Immutable domain vocabulary (records + enums)
├── book         Order book: resting orders, stop lists, analytics, snapshots, registry
├── matching     Matching strategies: one OrderMatcher per order type + factory
├── marketdata   Market price tracking, periodic snapshot service
└── engine       Orchestration: routing, stop lifecycle, the MatchingEngine entry point
```

Dependencies flow one way: `engine → matching → {book, marketdata} → model`. Every major
capability is an interface with a named standard implementation:

| Contract | Standard implementation |
|---|---|
| `OrderBook` | `PriceTimePriorityOrderBook` (TreeMap; O(1) live sorted views), `PriorityQueueOrderBook` (binary heaps + lazy deletion; fastest matching hot path, sorted views built on demand) |
| `OrderBookRegistry` | `InMemoryOrderBookRegistry` |
| `MarketPriceTracker` | `LastTradedPriceTracker` |
| `OrderMatcher` | `LimitOrderMatcher`, `MarketOrderMatcher`, `IocOrderMatcher`, `FokOrderMatcher` |
| `MatchingEngine` | `OrderMatchingService` |
| `SnapshotPublisher` | any lambda (feed, store, log) |

### Threading model

The engine is deliberately **single-threaded per book** (the LMAX-style model): no locks, fully
deterministic matching. Concurrency is achieved by sharding symbols across engines, not by sharing
books across threads. Time-based work (snapshotting) runs **in-band** on the matching thread so it
always sees a consistent book.

## The domain model (`com.oms.model`)

### `Order` — immutable record

| Field | Type | Meaning |
|---|---|---|
| `id` | `UUID` | unique order identity, preserved across amendments and stop activation |
| `symbol` | `String` | the instrument being traded (ticker/commodity); also the routing key |
| `side` | `Side` | `BUY` or `SELL` |
| `type` | `OrderType` | pricing / time-in-force behaviour (see below) |
| `quantity` | `BigDecimal` | amount of the underlying to trade; strictly positive |
| `price` | `BigDecimal` | limit price; `null` for MARKET and STOP orders |
| `stopPrice` | `BigDecimal` | trigger price; only for STOP and STOP_LIMIT orders |
| `timestamp` | `Instant` | when the order entered the system; drives time priority |
| `status` | `OrderStatus` | `PENDING`, `FULFILLED`, or `CANCELLED` |

`Order` is a record with a **compact constructor that validates every construction path** — an
invalid order cannot exist. The per-type validation matrix:

| `OrderType` | `price` | `stopPrice` |
|---|---|---|
| `LIMIT`, `IOC`, `FOK` | required, > 0 | must be null |
| `MARKET` | must be null | must be null |
| `STOP` | must be null | required, > 0 |
| `STOP_LIMIT` | required, > 0 | required, > 0 |

Since orders are immutable, editing goes through the **builder**: `Order.builder()` creates from
scratch; `order.toBuilder()` seeds a builder with the existing fields for amendments. Domain
helpers: `isMarket()`, `isStop()`, and `isStopTriggeredAt(price)` (a buy stop triggers at or above
its stop price, a sell stop at or below).

All prices/quantities are `BigDecimal` (no floating-point rounding), compared with `compareTo`, so
scale variants like `100` and `100.00` are the same price. Timestamps are UTC `Instant`s from an
injectable `Clock`, making every time-dependent behaviour deterministic in tests.

### `OrderType` — matching semantics

| Type | Crosses | Unfilled remainder |
|---|---|---|
| `LIMIT` | at its price or better | rests on the book |
| `MARKET` | any opposite order (two market orders need a reference price) | rests **at the top of the book**, ahead of all limits, time-ordered among market orders |
| `IOC` (immediate-or-cancel) | like a limit | cancelled — never rests |
| `FOK` (fill-or-kill) | like a limit, but only if the full quantity is executable up front | all-or-nothing: fills completely or cancels with **zero** trades and an untouched book |
| `STOP` | — dormant until triggered | activates as a `MARKET` order |
| `STOP_LIMIT` | — dormant until triggered | activates as a `LIMIT` order at its limit price |

### `Trade` — one execution

`Trade(buyOrderId, sellOrderId, symbol, price, quantity, timestamp)` — produced whenever an
incoming order crosses a resting order. Execution pricing rules:

1. Resting (maker) order has a price → trade at the **maker's price** (the aggressor gets any
   price improvement).
2. Maker is a market order → trade at the **incoming order's** price.
3. Both are market orders → trade at the symbol's **tracked market price** (see below); if no
   reference price exists yet, they cannot cross and both rest.

### `MatchResult` — outcome of one match

`MatchResult(trades, remainder)`: the executions in order, plus an `Optional<Order>` remainder —
a `PENDING` order now resting (or parked as a stop), or a `CANCELLED` one (IOC/FOK). Helpers:
`fullyFilled()`, `hasRestingRemainder()`. Trade lists are defensively copied and unmodifiable.

## The order book (`com.oms.book`)

`OrderBook` separates **primitives** (implementation-specific) from **analytics** (interface
default methods derived from the views, identical for every implementation).

### Priority ordering (`PriceTimePriorityOrderBook`)

Both sides are `TreeMap`s keyed by order id, with comparators that resolve ids back to orders:

1. **Market orders first**, ordered among themselves by arrival time.
2. **Limit orders by price** — bids descending (highest first), asks ascending (lowest first).
3. Ties break by **timestamp** (earliest first), then id (so equal-ranked orders never collide).

Mutations: `addOrder`, `removeOrder`, and `updateOrder(id, amendment)` — amendments are expressed
against a builder seeded from the existing order and the order is re-placed, so price/side changes
re-sort correctly while the id is preserved.

### Stop lists

Dormant stop orders live in two separate trigger-ordered lists, invisible to matching: buy stops
ascending by stop price (lowest trigger fires first as the market rises), sell stops descending.
That makes the stops fired by a traded price a **strict prefix** of each list, so
`pollTriggeredStops(price)` drains exactly the fired stops in O(k log n) without scanning the rest.

### Analytics (interface defaults)

- **`spread()`** — best ask minus best bid; empty if either side is empty or topped by a priceless
  market order.
- **`availableLiquidity(side)`** — total resting quantity on a side.
- **`availableLiquidity(side, limitPrice)`** — quantity an aggressor at that limit could actually
  execute against (market orders always count; the scan short-circuits at the first unreachable
  limit order). This powers FOK's all-or-nothing check.
- **`depth(maxLevels)`** — the standard level-2 **market depth** view: top-N `PriceLevel`s
  (price, total quantity, order count) per side, aggregated in a single adjacency scan; market
  orders are excluded (no price, no ladder).

### Snapshots

`OrderBookSnapshot.capture(...)` produces an immutable point-in-time copy of one book: both
resting sides in priority order, both stop lists, a venue-wide monotonic `sequence`, and a
`timestamp`. Snapshots offer the same `bestBuy`/`bestSell`/`spread`/`depth(n)` views as the live
book, and are unaffected by later book mutations.

### Registry

`OrderBookRegistry` routes each symbol to its own book — matching never crosses symbols.
`InMemoryOrderBookRegistry` supports **create-on-demand** (default, good for simulation) or
**strict** mode (only pre-`register`ed symbols are tradable, as venues do with reference data),
and takes a `Supplier<OrderBook>` so the book implementation is pluggable.

## Matching (`com.oms.matching`)

Strategy pattern: `OrderMatcher.match(order, book)` is the contract; `AbstractOrderMatcher` holds
the shared price-time-priority fill loop (walk the best opposite order while prices cross, reduce
or remove resting orders, rest/cancel the remainder) with three hooks:

- `crosses(incoming, resting)` — the crossing rule (the only thing LIMIT and MARKET differ on).
- `handleRemainder(...)` — rest (default) vs cancel (IOC/FOK).
- `canExecute(incoming, book)` — pre-trade check; FOK uses it to demand full executable liquidity
  **before** the first fill, guaranteeing all-or-nothing without unwinding.

`OrderMatcherFactory` selects the matcher by `OrderType` via an exhaustive switch — adding an
order type is a compile error until it is handled. Matchers are stateless and shared. Stop orders
never reach a matcher; the factory rejects them (the engine activates them first).

## Market data (`com.oms.marketdata`)

- **`MarketPriceTracker`** — each symbol's current market price. The standard policy
  (`LastTradedPriceTracker`) records every execution and returns the last-traded price. It is the
  reference price for market-vs-market matches and the **trigger source for stop orders**. Seed an
  opening price with `record(symbol, price)` to avoid the cold-start case.
- **`OrderBookSnapshotService`** — periodic snapshots of every registered book, with the interval
  fixed at application start (`Duration.ZERO` = every tick). The engine calls `tick()` after each
  processed order; a cycle runs only when the interval has elapsed. Each captured snapshot is kept
  (`latestSnapshot(symbol)`) and handed to the `SnapshotPublisher`. `snapshotNow()` forces a cycle
  (end-of-day capture, late-joiner refresh).

## The engine (`com.oms.engine`)

`MatchingEngine.match(order)` is the single entry point. `OrderMatchingService` orchestrates:

1. **Route** the order to its symbol's book via the registry.
2. **Stop orders**: park in the stop list — or activate immediately if the market already trades
   at/through the trigger. Others: match via the factory-selected matcher.
3. **Trigger processing**: after every match, poll the stop lists at the last-traded price and
   activate whatever fired — a `STOP` becomes a `MARKET` order, a `STOP_LIMIT` becomes a `LIMIT`
   order (id preserved, timestamp re-stamped to activation time, since a dormant stop earns no
   queue priority). Activations re-match immediately and may move the price, so the loop re-polls
   until quiet — **stop cascades** are handled to completion.
4. **Snapshot tick** (if configured), once the book has fully settled.

Trades produced by activated stops belong to those orders and are not folded into the incoming
order's `MatchResult` (like per-order execution reports).

### Wiring example

```java
var registry  = new InMemoryOrderBookRegistry();
var clock     = Clock.systemUTC();
var snapshots = new OrderBookSnapshotService(registry, Duration.ofSeconds(1), clock, feed::publish);
var engine    = new OrderMatchingService(registry, clock, snapshots);

engine.marketPriceTracker().record("AAPL", new BigDecimal("100")); // seed an opening price

Order buy = Order.builder()
        .id(UUID.randomUUID())
        .symbol("AAPL")
        .side(Side.BUY)
        .type(OrderType.LIMIT)
        .quantity(new BigDecimal("10"))
        .price(new BigDecimal("101.50"))
        .timestamp(clock.instant())
        .status(OrderStatus.PENDING)
        .build();

MatchResult result = engine.match(buy);
result.trades();                             // executions, maker-priced
result.remainder();                          // unfilled portion resting on the book, if any
engine.orderBook("AAPL").depth(5);           // top-5 market depth
engine.orderBook("AAPL").spread();           // current bid-ask spread
snapshots.latestSnapshot("AAPL");            // most recent periodic snapshot
```

## Testing

164 JUnit 5 tests cover the model validation matrix, book priority/stop/analytics behaviour,
every matcher's semantics (including FOK atomicity and stop cascades), multi-symbol isolation,
and snapshot timing (via a `MutableClock`). The order-book tests are **contract tests**
(`OrderBookContractTest`, `MarketDepthContractTest`) that run identically against both
`OrderBook` implementations, so the TreeMap and PriorityQueue books are provably
behaviour-equivalent. Shared builders live in `com.oms.support.TestOrders`.

```bash
./gradlew test
```

## Performance (JMH)

Benchmarks live in `src/jmh/java` (`OrderMatchingBenchmark`) and run with:

```bash
./gradlew jmh      # results land in build/results/jmh/results.txt
```

**Methodology.** The book is pre-populated with 2,000 resting limit orders (10 bid levels 90–99
and 10 ask levels 101–110, 100 one-unit orders per level). Every operation is self-balancing so
the book stays in steady state: a passive order is rested then cancelled; an aggressive fill
consumes the head of the best-ask queue and replenishes its tail. Order construction (UUID,
`BigDecimal`, builder validation) is inside the measured op — that is the real cost of an order
arriving. Modes: `Throughput` (ops/µs) and `SampleTime` (per-invocation latency distribution).
JMH 1.37, 1 fork, 3×1 s warmup + 5×1 s measurement per mode, single thread.

> Numbers below were measured on an Apple-silicon laptop (JDK 25, macOS). Treat them as
> **indicative, for comparing implementations and changes** — not production figures. For rigor,
> rerun on quiet, pinned-CPU hardware with more forks.

### Throughput

| Benchmark (one full op) | TreeMap book | PriorityQueue book | Δ |
|---|---|---|---|
| Passive limit: rest + cancel | 1.60 M ops/s | **4.30 M ops/s** | **~2.7× faster** |
| Aggressive limit: full fill + replenish | 1.12 M ops/s | **1.40 M ops/s** | +25% |
| Market order: full fill + replenish | 1.03 M ops/s | **1.41 M ops/s** | +37% |
| Depth top-10 levels (read) | **73 K ops/s** | 1.9 K ops/s | **~38× slower** |

### Latency (µs/op, SampleTime)

| Benchmark | Book | p50 | p90 | p99 | max |
|---|---|---|---|---|---|
| Passive rest + cancel | TreeMap | 0.58 | 0.67 | 0.83 | 475 |
| | PriorityQueue | **0.17** | **0.21** | **0.29** | **142** |
| Aggressive limit full fill | TreeMap | 0.88 | 1.00 | 1.17 | 971 |
| | PriorityQueue | **0.63** | **0.71** | **0.88** | **487** |
| Market order full fill | TreeMap | 0.88 | 0.96 | 1.17 | 507 |
| | PriorityQueue | **0.63** | **0.75** | **0.96** | **481** |
| Depth top-10 levels | TreeMap | **13.3** | **14.9** | **24.3** | **608** |
| | PriorityQueue | 519 | 574 | 713 | 2589 |

### Interpretation

- **The heap book wins the matching hot path across the board** — sub-microsecond medians and
  roughly halved tails. Inserting into a flat heap array beats rebalancing tree nodes (better
  constants, better cache locality), and a lazy-deletion cancel is essentially a `HashMap.remove`
  plus a tombstone counter. The 2.7× on rest-and-cancel matters most: on real venues the large
  majority of orders are cancelled, not filled.
- **The heap book loses badly on ordered reads.** Heaps cannot iterate in priority order, so its
  `buyOrders()`/`sellOrders()` views are O(n log n) sorted copies built per call — `depth(10)`
  goes from ~13 µs to ~520 µs (~38×). Everything view-derived (depth, price-limited liquidity
  scans, snapshots) inherits that cost.
- **Pick by workload.** Heavy order flow with rare depth queries → `PriorityQueueOrderBook`.
  Publishing L2 depth on every tick → `PriceTimePriorityOrderBook`. The production-grade answer
  beyond both is incrementally maintained per-price-level aggregates, which makes matching *and*
  depth cheap.
- Spikes past p99.9 in all benchmarks (tens to hundreds of µs) are JVM artifacts — GC from
  per-order allocation and OS scheduling — not algorithmic behaviour.
