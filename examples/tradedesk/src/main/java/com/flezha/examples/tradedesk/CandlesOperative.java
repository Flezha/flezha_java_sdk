package com.flezha.examples.tradedesk;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;
import javax.annotation.Nonnull;

/**
 * The desk's stream: trades in, one candle per minute out, and an order started when a candle
 * gives a signal.
 *
 * <p>A stream operative runs for as long as the desk does. Trades arrive on its entry channel,
 * are routed to an entity per symbol, and each entity folds its trades into the candle it is
 * building. A candle that closes leaves on its own channel and is handed to the step that decides
 * whether to trade.
 */
public final class CandlesOperative implements StreamOperative {

    /** One trade, as the exchange reports it. The entry channel decodes the JSON into this. */
    public record Trade(String symbol, double price, long ts) {}

    /** A finished minute for one symbol. */
    public record Candle(String symbol, long minute, int trades, double close) {}

    @Override
    @Nonnull
    public String factoryId() {
        return "candles";
    }

    /**
     * Trades enter on {@code trades} and are keyed by symbol, which is what gives each symbol an
     * entity of its own with its own state. The candle channel carries that symbol as its key and
     * is named as a sink, so every closed candle is on the plugin layer as well as handed to the
     * step that decides whether to trade.
     */
    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        c.input(Trade.class, "trades")
                .keyBy(Trade::symbol)
                .into(BuildCandles.class)
                .keyed()
                .sink("candles")
                .into(DecideTrade.class)
                .sink("signals");
    }
}
