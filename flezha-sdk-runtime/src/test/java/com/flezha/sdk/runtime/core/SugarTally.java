package com.flezha.sdk.runtime.core;

import com.flezha.sdk.authoring.StreamOperative;

/**
 * Records a derived change per input, which is what gives a restore fold a
 * journal of generated variants to read back.
 */
@StreamOperative(id = "sugar_tally_op")
public class SugarTally {
    public void run(String input, SugarTallyStateHandle state) {
        state.pushSeen(input);
        state.setN(state.get().n + 1);
    }
}
