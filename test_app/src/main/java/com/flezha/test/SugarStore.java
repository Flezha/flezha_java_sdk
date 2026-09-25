package com.flezha.test;

import com.flezha.sdk.authoring.UnboundedOperator;

/** Single-tasklet stateful short-form twin of {@link StoreInputTasklet}. */
@UnboundedOperator(id = "sugar_store_input", sink = "sugar_store_sink")
public class SugarStore {
    public String run(String input, SugarStoreStateHandle state) {
        state.pushText_inputs(input);
        return input;
    }
}
