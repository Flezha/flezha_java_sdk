package com.flezha.test;

import com.flezha.sdk.authoring.Operator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/**
 * Half of the refusal table's {@code shorthand_duplicate_id} pair. Not a fixture: nothing but that
 * row and the test beside it registers this class.
 */
@Operator(id = "plain", sink = "out")
public class ShorthandDupFlow implements Tasklet<String, Void, byte[]> {

    @Override
    public void run(String input, Ctx<Void, byte[]> ctx) throws TaskletException {
        ctx.emit(input.getBytes(StandardCharsets.UTF_8));
    }
}
