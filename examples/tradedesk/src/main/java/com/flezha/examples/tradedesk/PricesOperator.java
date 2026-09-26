package com.flezha.examples.tradedesk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flezha.examples.tradedesk.TrackPrices.Trade;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * The desk's stream: trades in, each price compared with its symbol's recent average, and an order
 * started when a price jumps far enough above it.
 *
 * <p>An unbounded operator runs for as long as the desk does. The exchange delivers several trades in
 * one message, so the wiring splits the message before anything else sees it, drops what the desk
 * does not follow, keeps only the fields the desk reads, and routes the rest to a cell per symbol.
 */
public final class PricesOperator implements UnboundedOperator {

    /**
     * One message from the exchange, carrying one or more trades. The exchange sends more fields
     * than the desk reads, and the default JSON decoding refuses a field the record does not name
     * unless the record says to ignore it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TradeMessage(List<Trade> data) {}

    @Override
    @Nonnull
    public String factoryId() {
        return "prices";
    }

    /**
     * Three transforms on the wiring, then two tasklets, and one place output leaves the desk.
     *
     * <p>{@code flatMap} turns one message into its trades, each routed on its own. {@code filter}
     * keeps the market orders, the trades that went through at whatever the price was.
     * {@code keyBy} names the cell each trade belongs to, which is what gives every
     * symbol its own state and its own order of arrival; it has to be the last link before
     * {@code into}, because it is evaluated on whatever the transforms above it produced. The three
     * run at the routing seam rather than as tasklets of their own: no cell, no mailbox, no
     * dispatch hop, which is why they must stay cheap and free of side effects.
     *
     * <p>A tasklet's output is an ordinary channel, so each stage is wired onto the one before it
     * with the same {@code into} the entry channel used, and the expression reads in flow order.
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
        c.input(TradeMessage.class, "trades")
                .flatMap(Trade.class, TradeMessage::data)
                .filter(trade -> "market".equals(trade.ord_type()))
                .keyBy(Trade::symbol)
                .into(TrackPricesTasklet.class)
                .into(BuyAboveAverageTasklet.class)
                .sink("signals")
                .sealed();
    }
}
