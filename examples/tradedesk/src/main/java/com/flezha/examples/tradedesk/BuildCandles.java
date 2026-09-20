package com.flezha.examples.tradedesk;

import com.flezha.examples.tradedesk.CandlesOperative.Candle;
import com.flezha.examples.tradedesk.CandlesOperative.Trade;
import com.flezha.sdk.procedure.Procedure;
import com.flezha.sdk.procedure.ProcedureCtx;
import com.flezha.sdk.procedure.ProcedureException;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.List;
import java.util.Optional;

/**
 * Collects one symbol's trades into a candle, and emits the candle when a trade from a later
 * minute arrives.
 *
 * <p>One entity per symbol, because the operative keys the channel by symbol, and the candle it is
 * collecting is that entity's state. Every change to it is recorded, so a restart brings back the
 * half-built candle rather than an empty one.
 *
 * <p>The changes it records are its own type, because opening a candle and adding to one write
 * different fields, and a change that is conditional or spans several fields has to be written
 * rather than derived. That is also why this stage implements {@link Procedure} directly instead
 * of taking the {@code @Procedure} short form the desk's stateless stages use.
 */
public final class BuildCandles
        implements Procedure<Trade, BuildCandles.Building, Candle, BuildCandles.Fold, WallClock, Void> {

    /** The candle this symbol is collecting, and where the one before it closed. */
    public static final class Building {
        public long minute = -1;
        public int trades;
        public double last;
        public double previousClose;
    }

    /** The two changes a trade makes: it opens a candle, or it adds to the open one. */
    public sealed interface Fold extends Change permits Fold.Opened, Fold.Traded {

        /** The first trade of a minute. */
        record Opened(long minute, double price) implements Fold {}

        /** A later trade in the same minute. */
        record Traded(double price) implements Fold {}
    }

    @Override
    public String name() {
        return "build_candles";
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(Building.class, Fold.Opened.class, Fold.Traded.class);
    }

    @Override
    public Building initState(Optional<String> entityKey, Optional<RestoreInitPayload> initPayload) {
        return new Building();
    }

    @Override
    public void applyChange(Building state, Fold change) {
        if (change instanceof Fold.Opened opened) {
            // Opening the next candle is what turns the running close into the previous one. It is
            // read from the state rather than carried in the change, so the fold stays the only
            // writer and a replay lands on the same value.
            state.previousClose = state.last;
            state.minute = opened.minute();
            state.trades = 1;
            state.last = opened.price();
        } else if (change instanceof Fold.Traded traded) {
            state.trades += 1;
            state.last = traded.price();
        }
    }

    @Override
    public void run(Trade trade, StateHandle<Building, Fold> state, ProcedureCtx ctx) throws ProcedureException {
        long minute = trade.ts() / 60_000;
        Building candle = state.get();

        if (candle.minute == minute) {
            state.apply(new Fold.Traded(trade.price()));
            return;
        }

        if (candle.minute >= 0) {
            // The trade belongs to a later minute, so the candle before it is final. It carries the
            // close before it, which the next stage needs and only this entity knows.
            ctx.emit(new Candle(
                    trade.symbol(), candle.minute, candle.trades, candle.last, candle.previousClose));
        }

        state.apply(new Fold.Opened(minute, trade.price()));
    }
}
