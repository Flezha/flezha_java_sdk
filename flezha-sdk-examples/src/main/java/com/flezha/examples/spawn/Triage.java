package com.flezha.examples.spawn;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/** Triages the alert the worker was spawned for. */
@Tasklet.Spec(name = "triage")
public final class Triage implements Tasklet<String, Void, String> {

    @Override
    public void run(String alert, Ctx<Void, String> ctx) throws TaskletException {
        ctx.emit("triaged:" + alert);
    }
}
