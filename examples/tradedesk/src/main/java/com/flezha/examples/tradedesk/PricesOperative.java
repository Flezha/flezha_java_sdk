package com.flezha.examples.tradedesk;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * The desk's stream: trades in, each price compared with its symbol's recent average, and an order
 * started when a price jumps far enough above it.
 *
 * <p>A stream operative runs for as long as the desk does. The exchange delivers several trades in
 * one frame, so the wiring splits the frame before anything else sees it, drops what the desk does
 * not trade on, and routes the rest to a cell per symbol.
 */
public final class PricesOperative implements StreamOperative {

    /** One frame from the exchange's feed, carrying however many trades happened in it. */
    public record TradeBatch(List<Trade> trades) {}

    /** One trade, as the exchange reports it. */
    public record Trade(String symbol, double price, int quantity) {}

    /** A trade's price beside the average of the prices its symbol traded at before it. */
    public record Move(String symbol, double price, double average, int prices) {}

    @Override
    @Nonnull
    public String factoryId() {
        return "prices";
    }

    /**
     * Three transforms on the wiring, then two tasklets, and two places output leaves the desk.
     *
     * <p>{@code flatMap} turns one frame into its trades, each routed on its own. {@code filter}
     * drops the feed's zero-quantity frames. {@code keyBy} names the cell each trade belongs to,
     * which is what gives every symbol its own state and its own order of arrival; it has to be
     * the last link before {@code into}, because it is evaluated on whatever the transforms above
     * it produced. The three run at the routing seam rather than as tasklets of their own: no
     * cell, no mailbox, no dispatch hop, which is why they must stay cheap and free of side
     * effects.
     *
     * <p>A tasklet's output is an ordinary channel, so each stage is wired onto the one before it
     * with the same {@code into} the entry channel used, and the expression reads in flow order.
     *
     * <p>{@code sink("moves")} sits in the middle of that chain, which is the point: a sink is an
     * address on the plugin layer, not the end of anything. Every move is delivered there for
     * whatever is listening, and the same value carries on to the next stage.
     *
     * <p>Only the first stage is keyed, because keying is declared per edge rather than inherited:
     * a plain {@code into} leaves its consumer single-cell. That is the right shape here, since
     * the second stage keeps nothing per symbol. A stage that did would need its own
     * {@code keyBy}, or one cell would hold every symbol at once.
     *
     * <p>{@code sealed()} states that the signals channel is where the desk's output ends. It
     * is purely type-level: the declared topology is the same without it, but a later
     * {@code into} on this chain no longer compiles.
     */
    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        c.input(TradeBatch.class, "trades")
                .flatMap(Trade.class, TradeBatch::trades)
                .filter(trade -> trade.quantity() > 0)
                .keyBy(Trade::symbol)
                .into(TrackPricesTasklet.class)
                .sink("moves")
                .into(BuyAboveAverageTasklet.class)
                .sink("signals")
                .sealed();
    }
}
