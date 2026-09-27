package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mirror of the Rust test app's {@code PoisonProbeTasklet}
 * (dead-letter-queue e2e): fails on the input {@code "poison"}
 * until an {@code "antidote"} input flips a process-global flag, so a
 * parked entry's replay succeeds after the fix.
 */
@Tasklet.Spec(name = "poison_probe_tasklet")
public final class PoisonProbeTasklet implements Tasklet<String, Void, String> {

    static final AtomicBoolean ANTIDOTE = new AtomicBoolean(false);

    @Override
    public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
        if ("antidote".equals(input)) {
            ANTIDOTE.set(true);
        } else if ("poison".equals(input) && !ANTIDOTE.get()) {
            throw new TaskletException("poison payload");
        }
        ctx.emit("ok:" + input);
    }
}
