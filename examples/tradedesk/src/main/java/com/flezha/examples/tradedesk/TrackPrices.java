package com.flezha.examples.tradedesk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flezha.sdk.authoring.State;
import com.flezha.sdk.authoring.Tasklet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Keeps a symbol's last ten prices, and compares each new trade with their average.
 *
 * <p>One cell per symbol, because the operator keys the channel by symbol, and the window is that
 * cell's state. Every change to it is recorded as it happens, so a runner that is killed and
 * started again finds the window it had rather than an empty one.
 */
@Tasklet(id = "track_prices")
public final class TrackPrices {

    /**
     * One trade, with only the fields the desk reads. It is decoded from the exchange's message,
     * which carries more fields than these, so the others are ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Trade(String symbol, double price, String ord_type) {}

    /** A trade's price beside the average of the prices its symbol traded at before it. */
    public record Move(String symbol, double price, double average) {}

    /** How many of a symbol's latest prices the average is taken over. */
    private static final int WINDOW = 10;

    /**
     * What a symbol's cell remembers: its latest prices, oldest first. {@code @State} derives the
     * changes that record it, one per way the field can change, so {@code pushBackPrices} and
     * {@code popFrontPrices} each write only the price they add or drop rather than the whole
     * window. It also generates {@code PriceWindowHandle}, the parameter {@code run} takes.
     */
    @State
    public static class PriceWindow {
        public Deque<Double> prices = new ArrayDeque<>();
    }

    public Optional<Move> run(Trade trade, PriceWindowHandle window) {
        Deque<Double> prices = window.get().prices;

        // A symbol's first trade has nothing to be compared with.
        Optional<Move> move = prices.isEmpty()
                ? Optional.empty()
                : Optional.of(new Move(trade.symbol(), trade.price(), average(prices)));

        window.pushBackPrices(trade.price());
        if (prices.size() > WINDOW) {
            window.popFrontPrices(1);
        }
        return move;
    }

    private static double average(Deque<Double> prices) {
        return prices.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }
}
