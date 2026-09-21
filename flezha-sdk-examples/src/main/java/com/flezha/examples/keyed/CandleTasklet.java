package com.flezha.examples.keyed;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * The running candle for one symbol and timeframe.
 *
 * <p>Notice there is no keying here at all. A keyed edge feeds this tasklet, so cells
 * appear on first sight of each key without the tasklet knowing about it.
 */
public final class CandleTasklet
        implements Tasklet<Slot, CandleTasklet.Candle, String, CandleTasklet.Tick, WallClock, Void> {

    /** Mutable, because {@link #applyChange} folds into it in place. */
    public static final class Candle {
        public double high = Double.MIN_VALUE;
        public double low = Double.MAX_VALUE;
        public long trades;
    }

    public record Tick(double price) implements Change {}

    @Override
    @Nonnull
    public String name() {
        return "candle";
    }

    @Override
    @Nonnull
    public Candle initState(@Nonnull Optional<String> cellKey, @Nonnull Optional<RestoreInitPayload> initPayload) {
        return new Candle();
    }

    @Override
    public void applyChange(@Nonnull Candle state, @Nonnull Tick change) {
        state.high = Math.max(state.high, change.price());
        state.low = Math.min(state.low, change.price());
        state.trades++;
    }

    @Override
    public void run(@Nonnull Slot input, @Nonnull StateHandle<Candle, Tick> state, @Nonnull TaskletCtx ctx)
            throws TaskletException {
        state.apply(new Tick(input.price()));
        Candle candle = state.get();
        ctx.emit(input.symbol() + " " + input.tf() + " h=" + candle.high + " l=" + candle.low);
    }
}
