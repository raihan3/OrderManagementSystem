package com.oms.bench;

import com.oms.book.InMemoryOrderBookRegistry;
import com.oms.book.MarketDepth;
import com.oms.book.OrderBook;
import com.oms.book.PriceTimePriorityOrderBook;
import com.oms.book.PriorityQueueOrderBook;
import com.oms.engine.OrderMatchingService;
import com.oms.model.MatchResult;
import com.oms.model.Order;
import com.oms.model.OrderStatus;
import com.oms.model.OrderType;
import com.oms.model.Side;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Latency and throughput benchmarks for the matching engine's hot paths.
 *
 * <p>The book is pre-populated with 2,000 resting limit orders (10 bid levels 90–99 and 10 ask
 * levels 101–110, 100 one-unit orders per level) and every benchmark operation is
 * <b>self-balancing</b>, so the book stays in steady state no matter how many invocations run:
 * a passive order is rested then cancelled; an aggressive fill consumes the head of the best ask
 * queue and immediately replenishes the tail. Order construction (UUID, BigDecimal, builder) is
 * deliberately inside the measured operation — that is the real cost of an order arriving.
 *
 * <p>{@link Mode#Throughput} reports ops/µs; {@link Mode#SampleTime} samples per-invocation
 * latency and reports the distribution (p50/p90/p99/p99.9...).
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class OrderMatchingBenchmark {

    private static final String SYMBOL = "BENCH";
    private static final BigDecimal ONE_UNIT = BigDecimal.ONE;
    private static final BigDecimal PASSIVE_BID = new BigDecimal("95");
    private static final BigDecimal BEST_ASK = new BigDecimal("101");

    /** Which {@link OrderBook} implementation the whole run exercises. */
    @Param({"TREE_MAP", "PRIORITY_QUEUE"})
    private String bookType;

    private OrderMatchingService engine;
    private OrderBook book;
    private long sequence;

    @Setup(Level.Trial)
    public void setUp() {

        Supplier<OrderBook> bookFactory = switch (bookType) {
            case "TREE_MAP" -> PriceTimePriorityOrderBook::new;
            case "PRIORITY_QUEUE" -> PriorityQueueOrderBook::new;
            default -> throw new IllegalArgumentException("unknown book type " + bookType);
        };
        var registry = new InMemoryOrderBookRegistry(true, bookFactory);
        engine = new OrderMatchingService(registry, Clock.systemUTC());
        book = registry.bookFor(SYMBOL);

        for (int level = 0; level < 10; level++) {
            BigDecimal bid = new BigDecimal(99 - level);   // 99 down to 90
            BigDecimal ask = new BigDecimal(101 + level);  // 101 up to 110
            for (int i = 0; i < 100; i++) {
                book.addOrder(order(Side.BUY, OrderType.LIMIT, bid));
                book.addOrder(order(Side.SELL, OrderType.LIMIT, ask));
            }
        }
    }

    /** A passive limit order's full lifecycle: rest mid-book, then cancel. */
    @Benchmark
    public boolean passiveLimitRestAndCancel() {
        MatchResult result = engine.match(order(Side.BUY, OrderType.LIMIT, PASSIVE_BID));
        return book.removeOrder(result.remainder().orElseThrow());
    }

    /** An aggressive limit order fully filling at the best ask, plus one replenishing insert. */
    @Benchmark
    public MatchResult aggressiveLimitFullFill() {
        MatchResult result = engine.match(order(Side.BUY, OrderType.LIMIT, BEST_ASK));
        book.addOrder(order(Side.SELL, OrderType.LIMIT, BEST_ASK));
        return result;
    }

    /** A market order fully filling at the best ask, plus one replenishing insert. */
    @Benchmark
    public MatchResult marketOrderFullFill() {
        MatchResult result = engine.match(order(Side.BUY, OrderType.MARKET, null));
        book.addOrder(order(Side.SELL, OrderType.LIMIT, BEST_ASK));
        return result;
    }

    /** Read-only level-2 depth aggregation across all ten price levels per side. */
    @Benchmark
    public MarketDepth depthTopTenLevels() {
        return book.depth(10);
    }

    private Order order(Side side, OrderType type, BigDecimal price) {
        return Order.builder()
                .id(UUID.randomUUID())
                .symbol(SYMBOL)
                .side(side)
                .type(type)
                .quantity(ONE_UNIT)
                .price(price)
                .timestamp(Instant.ofEpochMilli(sequence++))
                .status(OrderStatus.PENDING)
                .build();
    }
}
