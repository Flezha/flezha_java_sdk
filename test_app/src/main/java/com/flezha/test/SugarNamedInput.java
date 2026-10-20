package com.flezha.test;

import com.flezha.sdk.authoring.ShortRelay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/** The short form naming its entry channel {@code prices} rather than taking the default. */
@ShortRelay(factoryId = "sugar_named_input", output = "sugar_named_input_sink", input = "prices")
public class SugarNamedInput implements Tasklet<String, Void, byte[]> {

    @Override
    public void run(String input, Ctx<Void, byte[]> ctx) throws TaskletException {
        ctx.emit(input.getBytes(StandardCharsets.UTF_8));
    }
}
