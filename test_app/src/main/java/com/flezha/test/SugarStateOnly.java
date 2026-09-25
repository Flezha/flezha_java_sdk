package com.flezha.test;

import com.flezha.sdk.authoring.UnboundedOperator;

/**
 * The short form with no sink: its entry channel {@code ticks} feeds a tasklet that only keeps
 * state, so what it was sent is read back off the Cell rather than off a sink.
 */
@UnboundedOperator(id = "sugar_state_only", input = "ticks")
public class SugarStateOnly {
    public void run(String input, SugarStateOnlyStateHandle state) {
        state.pushSeen(input);
    }
}
