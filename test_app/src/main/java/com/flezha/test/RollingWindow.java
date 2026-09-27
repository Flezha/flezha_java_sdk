package com.flezha.test;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Canonical keyed-collection reference tasklet (authoring-contract §3 / §6): a rolling per-key
 * window, journaled as per-key push and pop changes, emitting only once a key's window is full.
 */
@Tasklet.Spec(name = "java_rolling_window")
public class RollingWindow implements Tasklet<WindowInput, RollingWindowState, Double> {
    static final int N = 3;

    /** Appends a value to the back of a key's window. */
    public record Pushed(String key, double value) implements Change<RollingWindowState> {
        @Override
        public void applyTo(RollingWindowState state) {
            state.window.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(value);
        }
    }

    /** Drops the first {@code n} values of a key's window. */
    public record Popped(String key, int n) implements Change<RollingWindowState> {
        @Override
        public void applyTo(RollingWindowState state) {
            Deque<Double> d = state.window.computeIfAbsent(key, k -> new ArrayDeque<>());
            int drop = Math.min(n, d.size());
            for (int i = 0; i < drop; i++) {
                d.pollFirst();
            }
        }
    }

    @Override
    public void run(WindowInput input, Ctx<RollingWindowState, Double> ctx) throws TaskletException {
        ctx.change(new Pushed(input.key(), input.value()));
        Deque<Double> w = ctx.state().window.get(input.key());
        if (w.size() > N) {
            ctx.change(new Popped(input.key(), w.size() - N));
        }
        if (w.size() < N) {
            return;
        }
        double sum = 0.0;
        for (double v : w) {
            sum += v;
        }
        ctx.emit(sum / N);
    }
}
