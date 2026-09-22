package com.flezha.examples.effectivelyonce;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/** A running total per symbol, written to a database that must not see duplicates. */
public final class RollupTasklet
        implements Tasklet<String, RollupTasklet.Totals, String, RollupTasklet.AddTick, WallClock, Void> {

    private static final int TICKS_PER_INTERVAL = 100;

    public static final class Totals {
        public long sum;
        public long ticks;
    }

    public record AddTick(long value) implements Change {}

    @Override
    @Nonnull
    public String name() {
        return "rollup";
    }

    /** One cell per symbol, so the durability is per symbol too. */
    @Override
    @Nonnull
    public List<String> cellKeys() {
        return List.of("BTC", "ETH");
    }

    @Override
    @Nonnull
    public List<String> cellsFor(@Nonnull String input) {
        return List.of(symbolOf(input));
    }

    @Override
    public void applyChange(@Nonnull Totals state, @Nonnull AddTick change) {
        state.sum += change.value();
        state.ticks++;
    }

    @Override
    @Nonnull
    public Totals initState(@Nonnull Optional<String> cellKey, @Nonnull Optional<RestoreInitPayload> initPayload) {
        return new Totals();
    }

    @Override
    public void run(@Nonnull String input, @Nonnull StateHandle<Totals, AddTick> state, @Nonnull TaskletCtx ctx)
            throws TaskletException {
        state.apply(new AddTick(valueOf(input)));

        // The key comes from the input, so a replay emits under the same key and the sink
        // updates rather than inserting. That is what makes a repeated emit harmless.
        ctx.emitKeyed(symbolOf(input), "sum=" + state.get().sum);

        if (state.get().ticks % TICKS_PER_INTERVAL == 0) {
            ctx.checkpoint();
        }
    }

    private static String symbolOf(String input) {
        return input.split(":", 2)[0];
    }

    private static long valueOf(String input) {
        return Long.parseLong(input.split(":", 2)[1]);
    }
}
