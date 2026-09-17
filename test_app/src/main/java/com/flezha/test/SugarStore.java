package com.flezha.test;

import com.flezha.sdk.authoring.OperativeKind;
import com.flezha.sdk.authoring.Procedure;

/** Single-procedure stateful short-form twin of {@link StoreInputProcedure}. */
@Procedure(id = "sugar_store_input", kind = OperativeKind.STREAM, sink = "sugar_store_sink")
public class SugarStore {
    public String run(String input, SugarStoreStateHandle state) {
        state.pushText_inputs(input);
        return input;
    }
}
