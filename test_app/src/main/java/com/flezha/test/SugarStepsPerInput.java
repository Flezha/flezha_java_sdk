package com.flezha.test;

import com.flezha.sdk.authoring.Operator;
import com.flezha.sdk.authoring.Operator.Run;
import com.flezha.sdk.authoring.Operator.Steps;
import com.flezha.sdk.operator.Operator.Each;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * The steps short form with its loop mode written: one iteration per message on
 * {@code input}, each emitting the text it received, and no completion after the first.
 */
@Operator(
        id = "sugar_steps_per_input",
        sink = "sugar_steps_per_input_out",
        run = @Run(steps = @Steps(each = Each.PER_INPUT)))
public class SugarStepsPerInput implements Tasklet<String, Void, String> {

    @Override
    public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
        ctx.emit(input);
    }
}
