package com.flezha.examples.state;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Counts the messages one cell has observed, retaining the count across restarts.
 *
 * <p>Compared with the echo example's tasklet, two things are new: a state class in place of
 * {@code Void}, and {@link Increment}, the change that writes it. Declaring them is the whole of
 * what makes a tasklet durable; the relay is unmodified.
 *
 * <p>State is not persisted by serialising the state object. The runtime records the ordered list
 * of changes applied and reconstructs the object after a restart by replaying that list, over the
 * most recent snapshot where one exists. Every constraint below follows from that.
 */
@Tasklet.Spec(name = "count")
public final class CountTasklet implements Tasklet<String, CountTasklet.Counter, String> {

    /**
     * The per-cell state. Ordinary mutable fields, written exclusively by {@link Increment#applyTo};
     * mutation from any other method is not recorded and does not survive reconstruction. Its
     * no-arg constructor is the initial value of a new cell.
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
     * <p>A record is the natural shape, and one change may write several fields at once, as this
     * one does. Production tasklets typically declare several change types, one per kind of
     * mutation.
     */
    public record Increment(long atMs) implements Change<Counter> {

        /**
         * The only method permitted to write state. The runtime invokes it when the change is
         * applied, and again for every logged change during reconstruction.
         *
         * <p>It must apply the change and do nothing else: no emitting, no clock reads, no external
         * calls. It will be executed again during recovery, potentially on a different host, and
         * any side effect performed here is repeated each time.
         */
        @Override
        public void applyTo(Counter counter) {
            counter.count++;
            counter.lastSeenMs = atMs;
        }
    }

    @Override
    public void run(String input, Ctx<Counter, String> ctx) throws TaskletException {
        // Mutation goes through ctx.change, never through a direct assignment. Writing
        // `ctx.state().count++` here compiles and behaves correctly for as long as the process
        // survives, but the increment is absent from the change log and therefore absent from the
        // reconstructed cell. The failure surfaces only after a restart.
        //
        // ctx.now() rather than System.currentTimeMillis(), for a related reason: this method is
        // executed again during recovery and must produce the same result. The runtime evaluates
        // the clock once per invocation and serves the same value on replay, whereas the system
        // clock would return a different one and the reconstructed state would diverge from the
        // state originally observed.
        ctx.change(new Increment(ctx.now()));

        // ctx.state() reflects changes applied earlier in this invocation, so the count is already
        // current.
        ctx.emit("count=" + ctx.state().count);
    }
}
