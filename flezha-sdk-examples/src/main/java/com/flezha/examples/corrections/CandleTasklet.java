package com.flezha.examples.corrections;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * A one-minute candle. Input is {@code <epochMillis>:<price>}.
 *
 * <p>It writes {@link #eventTime}, and writing it is what makes the tasklet's clock event time:
 * {@code ctx.now()} is then the message's own timestamp rather than the moment it was processed.
 */
@Tasklet.Spec(name = "candle")
public final class CandleTasklet implements Tasklet<String, CandleTasklet.Candle, String> {

    private static final long ONE_MINUTE_MS = 60_000L;

    public static final class Candle {
        public long open;
        public long high;
        public long low;
        public long close;
    }

    public record Tick(long price) implements Change<Candle> {
        @Override
        public void applyTo(Candle candle) {
            if (candle.open == 0) {
                candle.open = price;
                candle.low = price;
            }
            candle.high = Math.max(candle.high, price);
            candle.low = Math.min(candle.low, price);
            candle.close = price;
        }
    }

    /** Where the event time comes from: the timestamp in front of the price. */
    @Override
    public long eventTime(String input) {
        return Long.parseLong(input.split(":", 2)[0]);
    }

    @Override
    public void run(String input, Ctx<Candle, String> ctx) throws TaskletException {
        ctx.change(new Tick(Long.parseLong(input.split(":", 2)[1])));

        // ctx.now() is the event time, so on a correction replay it is the late message's own
        // timestamp. Bucketing by it puts the corrected candle where it always belonged.
        long bucket = ctx.now() / ONE_MINUTE_MS;

        ctx.emitKeyed(String.valueOf(bucket), "close=" + ctx.state().close);
    }
}
