package com.flezha.test;

import com.flezha.sdk.authoring.StreamOperative;

/** Single-procedure stateful short-form twin of {@link StoreInputProcedure}. */
@StreamOperative(id = "sugar_store_input", sink = "sugar_store_sink")
public class SugarStore {
    public String run(String input, SugarStoreStateHandle state) {
        state.pushText_inputs(input);
        return input;
    }
}
