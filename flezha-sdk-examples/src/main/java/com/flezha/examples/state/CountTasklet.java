package com.flezha.examples.state;

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
 * Counts the messages one cell has observed, retaining the count across restarts.
 *
 * <p>Unlike the echo example's {@code @Tasklet} class, this one implements {@link Tasklet}
 * directly, because the change it records is its own: {@link Increment} updates two fields at once,
 * which a {@code @State} class cannot derive. Declaring a change type is the whole of what makes a
 * tasklet durable; the operator is unmodified.
 *
 * <p>State is not persisted by serialising the state object. The runtime records the ordered list
 * of changes applied and reconstructs the object after a restart by replaying that list, over the
 * most recent snapshot where one exists. Every constraint below follows from that.
 */
public final class CountTasklet
        implements Tasklet<String, CountTasklet.Counter, String, CountTasklet.Increment, WallClock, Void> {

    /**
     * The per-cell state. Ordinary mutable fields, written exclusively by {@link #applyChange};
     * mutation from any other method is not recorded and does not survive reconstruction.
     */
    public static final class Counter {
        public long count;
        public long lastSeenMs;
    }

    /**
     * A single recorded mutation. This is the value that is persisted, so it must carry everything
     * required to reapply the mutation later. The timestamp is therefore a component rather than
     * something read during replay, when the original value would no longer be available.
     *
     * <p>A record is the natural shape. Production tasklets typically declare several change
     * types, one per kind of mutation, commonly as a sealed hierarchy.
     */
    public record Increment(long atMs) implements Change {}

    @Override
    @Nonnull
    public String name() {
        return "count";
    }

    /**
     * The only method permitted to write state. The runtime invokes it when a change is applied,
     * and again for every logged change during reconstruction.
     *
     * <p>It must apply the change and do nothing else: no emitting, no clock reads, no external
     * calls. It will be executed again during recovery, potentially on a different host, and any
     * side effect performed here is repeated each time.
     */
    @Override
    public void applyChange(@Nonnull Counter state, @Nonnull Increment change) {
        state.count++;
        state.lastSeenMs = change.atMs();
    }

    /**
     * The initial value for a new cell. On a resume the runtime restores the most recent snapshot
     * and folds the change log over whatever this returns, so it need only be correct for a cold
     * start.
     */
    @Override
    @Nonnull
    public Counter initState(@Nonnull Optional<String> cellKey, @Nonnull Optional<RestoreInitPayload> initPayload) {
        return new Counter();
    }

    @Override
    public void run(@Nonnull String input, @Nonnull StateHandle<Counter, Increment> state, @Nonnull TaskletCtx ctx)
            throws TaskletException {
        // Mutation goes through apply, never through a direct assignment. Writing
        // `state.get().count++` here compiles and behaves correctly for as long as the process
        // survives, but the increment is absent from the change log and therefore absent from the
        // reconstructed cell. The failure surfaces only after a restart.
        //
        // ctx.now() rather than System.currentTimeMillis(), for a related reason: this method is
        // executed again during recovery and must produce the same result. The runtime evaluates
        // the declared clock once per invocation and serves the same value on replay, whereas the
        // system clock would return a different one and the reconstructed state would diverge from
        // the state originally observed.
        state.apply(new Increment(ctx.now()));

        // state.get() reflects changes applied earlier in this invocation, so the count is already
        // current.
        ctx.emit("count=" + state.get().count);
    }
}
