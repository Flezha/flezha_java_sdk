package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mirror of the Rust test app's {@code PoisonProbeTasklet}
 * (dead-letter-queue e2e): fails on the input {@code "poison"}
 * until an {@code "antidote"} input flips a process-global flag, so a
 * parked entry's replay succeeds after the fix.
 */
public final class PoisonProbeTasklet implements Tasklet<String, Object, String, NoChange, WallClock, Void> {

    static final AtomicBoolean ANTIDOTE = new AtomicBoolean(false);

    @Override
    public String name() {
        return "poison_probe_tasklet";
    }

    @Override
    public Object initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return new Object();
    }

    @Override
    public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) throws TaskletException {
        if ("antidote".equals(input)) {
            ANTIDOTE.set(true);
        } else if ("poison".equals(input) && !ANTIDOTE.get()) {
            throw new TaskletException("poison payload");
        }
        ctx.emit("ok:" + input);
    }
}
