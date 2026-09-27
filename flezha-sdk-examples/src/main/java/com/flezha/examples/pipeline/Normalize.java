package com.flezha.examples.pipeline;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * The first stage: normalises the raw line before any later stage observes it.
 *
 * <p>Deliberately trivial, so that the wiring rather than the logic is what the example teaches. A
 * stage has no knowledge of what follows it, which is what allows one to be reordered or reused in
 * another graph without modification.
 */
@Tasklet.Spec(name = "normalize")
public final class Normalize implements Tasklet<String, Void, String> {

    @Override
    public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
        ctx.emit(input.trim().toLowerCase());
    }
}
