package com.flezha.examples.keyed;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * The running candle for one symbol and timeframe.
 *
 * <p>Notice there is no keying here at all. A keyed edge feeds this tasklet, so cells
 * appear on first sight of each key without the tasklet knowing about it.
 */
@Tasklet.Spec(name = "candle")
public final class CandleTasklet implements Tasklet<Slot, CandleTasklet.Candle, String> {

    /** Mutable, because {@link Tick#applyTo} folds into it in place. */
    public static final class Candle {
        public double high = Double.MIN_VALUE;
        public double low = Double.MAX_VALUE;
        public long trades;
    }

    public record Tick(double price) implements Change<Candle> {
        @Override
        public void applyTo(Candle candle) {
            candle.high = Math.max(candle.high, price);
            candle.low = Math.min(candle.low, price);
            candle.trades++;
        }
    }

    @Override
    public void run(Slot input, Ctx<Candle, String> ctx) throws TaskletException {
        ctx.change(new Tick(input.price()));
        Candle candle = ctx.state();
        ctx.emit(input.symbol() + " " + input.tf() + " h=" + candle.high + " l=" + candle.low);
    }
}
