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
 * one message, so the wiring splits the message before anything else sees it, drops what the desk
 * does not follow, keeps only the fields the desk reads, and routes the rest to a cell per symbol.
 */
public final class PricesOperative implements StreamOperative {

    /** One message from the exchange, in Kraken's WebSocket v2 format, carrying one or more trades. */
    public record TradeMessage(String channel, String type, List<ExchangeTrade> data) {}

    /**
     * One trade, field for field as the exchange reports it. Every field is declared because the
     * default JSON decoding refuses a field the record does not name.
     */
    public record ExchangeTrade(
            String symbol, String side, double price, double qty, String ord_type, long trade_id, String timestamp) {}

    /** One trade as the desk reads it. The exchange's other fields stop at the {@code map}. */
    public record Trade(String symbol, double price) {}

    /** A trade's price beside the average of the prices its symbol traded at before it. */
    public record Move(String symbol, double price, double average, int prices) {}

    @Override
    @Nonnull
    public String factoryId() {
        return "prices";
    }

    /**
     * Four transforms on the wiring, then two tasklets, and two places output leaves the desk.
     *
     * <p>{@code flatMap} turns one message into its trades, each routed on its own. {@code filter}
     * keeps the market orders, the trades that went through at whatever the price was.
     * {@code map} turns the exchange's trade into the desk's own, so the exchange's format goes no
     * further. {@code keyBy} names the cell each trade belongs to, which is what gives every
     * symbol its own state and its own order of arrival; it has to be the last link before
     * {@code into}, because it is evaluated on whatever the transforms above it produced. The four
     * run at the routing seam rather than as tasklets of their own: no cell, no mailbox, no
     * dispatch hop, which is why they must stay cheap and free of side effects.
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
        c.input(TradeMessage.class, "trades")
                .flatMap(ExchangeTrade.class, TradeMessage::data)
                .filter(trade -> "market".equals(trade.ord_type()))
                .map(Trade.class, trade -> new Trade(trade.symbol(), trade.price()))
                .keyBy(Trade::symbol)
                .into(TrackPricesTasklet.class)
                .sink("moves")
                .into(BuyAboveAverageTasklet.class)
                .sink("signals")
                .sealed();
    }
}
