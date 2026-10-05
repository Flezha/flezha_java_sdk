package com.flezha.examples.effectivelyonce;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * A running total per symbol, written to a database that must not see duplicates. Input is
 * {@code <symbol>:<value>}, such as {@code BTC/USD:42}.
 *
 * <p>One cell per symbol, so the durability is per symbol too: {@code keys} names the cells and
 * {@link #cellsFor} sends each input to its own.
 */
@Tasklet.Spec(
        name = "rollup",
        keys = {"BTC/USD", "ETH/USD"})
public final class RollupTasklet implements Tasklet<String, RollupTasklet.Totals, String> {

    private static final int TICKS_PER_INTERVAL = 100;

    public static final class Totals {
        public long sum;
        public long ticks;
    }

    public record AddTick(long value) implements Change<Totals> {
        @Override
        public void applyTo(Totals totals) {
            totals.sum += value;
            totals.ticks++;
        }
    }

    @Override
    @Nonnull
    public List<String> cellsFor(String input) {
        return List.of(symbolOf(input));
    }

    @Override
    public void run(String input, Ctx<Totals, String> ctx) throws TaskletException {
        ctx.change(new AddTick(valueOf(input)));

        // The key comes from the input, so a replay emits under the same key and the output
        // updates rather than inserting. That is what makes a repeated emit harmless.
        ctx.emitKeyed(symbolOf(input), "sum=" + ctx.state().sum);

        if (ctx.state().ticks % TICKS_PER_INTERVAL == 0) {
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
