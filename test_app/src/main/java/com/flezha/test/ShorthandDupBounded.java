package com.flezha.test;

import com.flezha.sdk.authoring.BoundedOperator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/**
 * The other half of {@code shorthand_duplicate_id}: a bounded operator claiming the factory id
 * {@link ShorthandDupUnbounded} holds, which is what proves the two kinds share one namespace.
 */
@BoundedOperator(id = "plain", sink = "twin_sink")
public class ShorthandDupBounded implements Tasklet<String, Void, byte[]> {

    @Override
    public void run(String input, Ctx<Void, byte[]> ctx) throws TaskletException {
        ctx.emit(input.getBytes(StandardCharsets.UTF_8));
    }
}
