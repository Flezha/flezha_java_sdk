package com.flezha.test;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.ArrayList;
import java.util.List;

/** Per-key map changes (set a value, append, drain the front) beside a flat field. */
@Tasklet.Spec(name = "java_keyed_collections")
public class KeyedCollections implements Tasklet<WindowInput, KeyedCollectionsState, Void> {

    /** Sets the latest value of a key. */
    public record LastSet(String key, double value) implements Change<KeyedCollectionsState> {
        @Override
        public void applyTo(KeyedCollectionsState state) {
            state.last.put(key, value);
        }
    }

    /** Appends a value to a key's closes. */
    public record ClosePushed(String key, double value) implements Change<KeyedCollectionsState> {
        @Override
        public void applyTo(KeyedCollectionsState state) {
            state.closes.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
    }

    /** Drops the first {@code n} of a key's closes. */
    public record ClosesDrained(String key, int n) implements Change<KeyedCollectionsState> {
        @Override
        public void applyTo(KeyedCollectionsState state) {
            List<Double> closes = state.closes.computeIfAbsent(key, k -> new ArrayList<>());
            closes.subList(0, Math.min(n, closes.size())).clear();
        }
    }

    /** Sets the tick count. */
    public record TicksSet(long ticks) implements Change<KeyedCollectionsState> {
        @Override
        public void applyTo(KeyedCollectionsState state) {
            state.ticks = ticks;
        }
    }

    @Override
    public void run(WindowInput input, Ctx<KeyedCollectionsState, Void> ctx) {
        ctx.change(new LastSet(input.key(), input.value()));
        ctx.change(new ClosePushed(input.key(), input.value()));
        List<Double> closes = ctx.state().closes.get(input.key());
        if (closes.size() > 3) {
            ctx.change(new ClosesDrained(input.key(), closes.size() - 3));
        }
        ctx.change(new TicksSet(ctx.state().ticks + 1));
    }
}
