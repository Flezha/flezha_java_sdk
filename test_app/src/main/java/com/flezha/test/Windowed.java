package com.flezha.test;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Static-cell tasklet whose rolling-window length is encoded in the cell key, supplied by
 * {@code initState}: the state class's no-arg constructor cannot derive it. Mirrors the market
 * {@code sma}/{@code ohlc} tasklets and is the Java parity twin of the Rust {@code Windowed}
 * tasklet.
 */
@Tasklet.Spec(name = "java_windowed", keys = {"win_2", "win_3"})
public class Windowed implements Tasklet<WindowInput, WindowedState, Double> {

    /** Appends a value to a key's closes. */
    public record ClosePushed(String key, double value) implements Change<WindowedState> {
        @Override
        public void applyTo(WindowedState state) {
            state.closes.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
    }

    /** Drops the first {@code n} of a key's closes. */
    public record ClosesDrained(String key, int n) implements Change<WindowedState> {
        @Override
        public void applyTo(WindowedState state) {
            List<Double> closes = state.closes.computeIfAbsent(key, k -> new ArrayList<>());
            closes.subList(0, Math.min(n, closes.size())).clear();
        }
    }

    @Override
    public List<String> cellsFor(WindowInput input) {
        return List.of("win_2", "win_3");
    }

    @Override
    public WindowedState initState(Optional<String> cellKey) {
        WindowedState s = new WindowedState();
        s.window = cellKey.equals(Optional.of("win_2")) ? 2 : 3;
        return s;
    }

    @Override
    public void run(WindowInput input, Ctx<WindowedState, Double> ctx) throws TaskletException {
        int window = ctx.state().window;
        ctx.change(new ClosePushed(input.key(), input.value()));
        List<Double> c = ctx.state().closes.get(input.key());
        if (c.size() > window) {
            ctx.change(new ClosesDrained(input.key(), c.size() - window));
        }
        if (c.size() < window) {
            return;
        }
        double sum = 0.0;
        for (double v : c) {
            sum += v;
        }
        ctx.emit(sum / window);
    }
}
