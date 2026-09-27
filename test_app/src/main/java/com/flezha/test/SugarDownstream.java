package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/** Downstream tasklet of the 2-tasklet reference operator; it keeps no state. */
@Tasklet.Spec(name = "macro_static_downstream_tasklet")
public class SugarDownstream implements Tasklet<String, Void, byte[]> {

    @Override
    public void run(String input, Ctx<Void, byte[]> ctx) throws TaskletException {
        ctx.emit(input.getBytes(StandardCharsets.UTF_8));
    }
}
