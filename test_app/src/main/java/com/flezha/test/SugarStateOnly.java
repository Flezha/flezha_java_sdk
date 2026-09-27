package com.flezha.test;

import com.flezha.sdk.authoring.UnboundedOperator;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * The short form with no sink: its entry channel {@code ticks} feeds a tasklet that only keeps
 * state, so what it was sent is read back off the Cell rather than off a sink.
 */
@UnboundedOperator(id = "sugar_state_only", input = "ticks")
public class SugarStateOnly implements Tasklet<String, SugarStateOnlyState, Void> {

    /** One input the Cell has seen. */
    public record Seen(String input) implements Change<SugarStateOnlyState> {
        @Override
        public void applyTo(SugarStateOnlyState state) {
            state.seen.add(input);
        }
    }

    @Override
    public void run(String input, Ctx<SugarStateOnlyState, Void> ctx) throws TaskletException {
        ctx.change(new Seen(input));
    }
}
