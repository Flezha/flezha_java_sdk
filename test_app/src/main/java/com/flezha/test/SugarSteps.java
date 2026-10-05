package com.flezha.test;

import com.flezha.sdk.authoring.Relay;
import com.flezha.sdk.authoring.Relay.Run;
import com.flezha.sdk.authoring.Relay.Steps;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/**
 * The steps short form: one declaration is a whole {@code once} steps relay, run on its
 * first message on {@code order} and completing after one run.
 */
@Relay(id = "sugar_steps", output = "sugar_steps_placed", input = "order", run = @Run(steps = @Steps))
public class SugarSteps implements Tasklet<String, Void, byte[]> {

    @Override
    public void run(String order, Ctx<Void, byte[]> ctx) throws TaskletException {
        ctx.emit(order.getBytes(StandardCharsets.UTF_8));
    }
}
