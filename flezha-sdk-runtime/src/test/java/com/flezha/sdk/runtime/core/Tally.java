package com.flezha.sdk.runtime.core;

import com.flezha.sdk.authoring.Relay;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.ArrayList;
import java.util.List;

/**
 * A short form recording two nested change records per input, which is what gives a restore fold
 * a journal of discovered types to read back.
 */
@Relay(id = "tally_op")
public class Tally implements Tasklet<String, Tally.TallyState, Void> {

    public static class TallyState {
        public long n;

        public List<String> seen = new ArrayList<>();
    }

    public record Seen(String value) implements Change<TallyState> {
        @Override
        public void applyTo(TallyState state) {
            state.seen.add(value);
        }
    }

    public record Counted() implements Change<TallyState> {
        @Override
        public void applyTo(TallyState state) {
            state.n++;
        }
    }

    @Override
    public void run(String input, Ctx<TallyState, Void> ctx) {
        ctx.change(new Seen(input));
        ctx.change(new Counted());
    }
}
