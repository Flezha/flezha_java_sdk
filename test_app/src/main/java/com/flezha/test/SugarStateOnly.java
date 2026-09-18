package com.flezha.test;

import com.flezha.sdk.authoring.StreamOperative;

/**
 * The short form with no sink: its entry channel {@code ticks} feeds a procedure that only keeps
 * state, so what it was sent is read back off the Entity rather than off a sink.
 */
@StreamOperative(id = "sugar_state_only", input = "ticks")
public class SugarStateOnly {
    public void run(String input, SugarStateOnlyStateHandle state) {
        state.pushSeen(input);
    }
}
