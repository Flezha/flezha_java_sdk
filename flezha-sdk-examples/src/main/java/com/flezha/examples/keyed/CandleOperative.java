package com.flezha.examples.keyed;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Turns one stream into many independent workers.
 *
 * <p>The three calls before {@code into} run on the wiring itself, not inside a tasklet, so they
 * cost no cell, no mailbox and no dispatch hop. Reshaping a value there is cheap; parsing a
 * megabyte there is not, so keep them small.
 */
public final class CandleOperative implements StreamOperative {

    private static final List<String> TIMEFRAMES = List.of("1m", "5m", "1h", "1d");

    @Override
    @Nonnull
    public String factoryId() {
        return "candles";
    }

    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        // filter discards what you do not want, flatMap turns one value into several, and keyBy
        // decides which cell each one belongs to. keyBy is total: it always names a cell and
        // never declines, which is why the discarding is filter's job and comes first.
        //
        // Cells are created on first sight of a key, so four timeframes across a hundred symbols
        // is four hundred independent candles without anything being declared up front.
        c.input(Trade.class, "trades")
                .filter(t -> t.qty() > 0)
                .flatMap(Slot.class, CandleOperative::explode)
                .keyBy(slot -> slot.symbol() + "@" + slot.tf())
                .into(CandleTasklet.class)
                .keyed()
                .sink("candles");
    }

    /** One trade becomes one slot per timeframe. Must be pure, and should be cheap. */
    private static List<Slot> explode(Trade t) {
        return TIMEFRAMES.stream()
                .map(tf -> new Slot(t.symbol(), tf, t.price()))
                .toList();
    }
}
