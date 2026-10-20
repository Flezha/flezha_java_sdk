package com.flezha.test;

import com.flezha.sdk.authoring.ShortRelay;
import com.flezha.sdk.authoring.ShortRelay.Run;
import com.flezha.sdk.authoring.ShortRelay.Steps;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/**
 * The other half of {@code shorthand_duplicate_id}: a steps relay claiming the factory id
 * {@link ShorthandDupFlow} holds, which is what proves a flow and a steps relay share one namespace.
 */
@ShortRelay(factoryId = "plain", output = "twin_sink", run = @Run(steps = @Steps))
public class ShorthandDupSteps implements Tasklet<String, Void, byte[]> {

    @Override
    public void run(String input, Ctx<Void, byte[]> ctx) throws TaskletException {
        ctx.emit(input.getBytes(StandardCharsets.UTF_8));
    }
}
