package com.flezha.examples.corrections;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.EventTimeFrom;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * A one-minute candle. Input is {@code <epochMillis>:<price>}.
 *
 * <p>This implements {@link Tasklet} directly rather than through {@code @Tasklet}, because its
 * clock is {@link EventTimeFrom}, a type argument the annotation does not take.
 */
public final class CandleTasklet
        implements Tasklet<String, CandleTasklet.Candle, String, CandleTasklet.Tick, EventTimeFrom, Void> {

    private static final long ONE_MINUTE_MS = 60_000L;

    public static final class Candle {
        public long open;
        public long high;
        public long low;
        public long close;
    }

    public record Tick(long price) implements Change {}

    @Override
    @Nonnull
    public String name() {
        return "candle";
    }

    /**
     * Where the event time comes from. Rust binds this through a type bound; Java's erasure
     * cannot, so declaring {@code EventTimeFrom} without overriding this fails at registration.
     */
    @Override
    public long eventTime(@Nonnull String input) {
        return Long.parseLong(input.split(":", 2)[0]);
    }

    @Override
    public boolean eventTimeOverridden() {
        return true;
    }

    @Override
    public void applyChange(@Nonnull Candle state, @Nonnull Tick tick) {
        if (state.open == 0) {
            state.open = tick.price();
            state.low = tick.price();
        }
        state.high = Math.max(state.high, tick.price());
        state.low = Math.min(state.low, tick.price());
        state.close = tick.price();
    }

    @Override
    @Nonnull
    public Candle initState(@Nonnull Optional<String> cellKey, @Nonnull Optional<RestoreInitPayload> initPayload) {
        return new Candle();
    }

    @Override
    public void run(@Nonnull String input, @Nonnull StateHandle<Candle, Tick> state, @Nonnull TaskletCtx ctx)
            throws TaskletException {
        state.apply(new Tick(Long.parseLong(input.split(":", 2)[1])));

        // ctx.now() is the event time, so on a correction replay it is the late message's own
        // timestamp. Bucketing by it puts the corrected candle where it always belonged.
        long bucket = ctx.now() / ONE_MINUTE_MS;

        ctx.emitKeyed(String.valueOf(bucket), "close=" + state.get().close);
    }
}
