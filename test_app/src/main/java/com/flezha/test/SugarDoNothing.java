package com.flezha.test;

import com.flezha.sdk.authoring.ShortRelay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/**
 * Single-tasklet stateless short-form twin of {@link DoNothingTasklet}: the annotation makes this
 * tasklet a whole relay, and {@code Main} registers this class.
 */
@ShortRelay(factoryId = "sugar_do_nothing", output = "sugar_do_nothing_echo")
public class SugarDoNothing implements Tasklet<String, Void, byte[]> {

    @Override
    public void run(String input, Ctx<Void, byte[]> ctx) throws TaskletException {
        ctx.emit(input.getBytes(StandardCharsets.UTF_8));
    }
}
