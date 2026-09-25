package com.flezha.test;

import com.flezha.sdk.authoring.BoundedOperator;
import com.flezha.sdk.operator.LoopMode;

/**
 * The bounded short form with its loop mode written: one iteration per message on
 * {@code input}, each returning the text it received, and no completion after the first.
 */
@BoundedOperator(
        id = "sugar_bounded_per_input",
        sink = "sugar_bounded_per_input_out",
        loopMode = LoopMode.PER_INPUT)
public class SugarBoundedPerInput {
    public String run(String input) {
        return input;
    }
}
