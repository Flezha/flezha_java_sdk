package com.flezha.test;

import com.flezha.sdk.authoring.BoundedOperator;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * The bounded short form with its loop mode written: one iteration per message on
 * {@code input}, each emitting the text it received, and no completion after the first.
 */
@BoundedOperator(
        id = "sugar_bounded_per_input",
        sink = "sugar_bounded_per_input_out",
        loopMode = LoopMode.PER_INPUT)
public class SugarBoundedPerInput implements Tasklet<String, Void, String> {

    @Override
    public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
        ctx.emit(input);
    }
}
