package com.flezha.examples.pipeline;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * The second stage. Its input is whatever the first stage emitted, so the declared types must
 * agree: {@code Normalize} emits a {@code String} and this consumes one.
 *
 * <p>Nothing here indicates a position in a chain. From this class's perspective messages simply
 * arrive, which is the same contract the first stage has.
 */
@Tasklet.Spec(name = "shout")
public final class Shout implements Tasklet<String, Void, String> {

    @Override
    public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
        ctx.emit(input.toUpperCase() + "!");
    }
}
