package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/**
 * Shared base for the crash-resume e2e's durable {@code String -> String} steps
 * (§7c.3). A concrete task declares only its {@code @Tasklet.Spec} name (and,
 * for a saga step, overrides {@link #compensate}). {@code run} commits one
 * idempotent {@link Insert} Change keyed by the task name — so a Model-A resume
 * re-run over restored state is a no-op — and passes the input on. Mirrors the
 * Rust test app's {@code durable_step!} macro.
 */
public abstract class DurableStep implements Tasklet<String, SeenState, String> {

    // A durable operator must register its Change type for the Kryo codec.
    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(Insert.class, SeenState.class);
    }

    @Override
    public void run(String input, Ctx<SeenState, String> ctx) throws TaskletException {
        ctx.change(new Insert(name()));
        ctx.emit(input);
    }

    /** The name the concrete step declares in its {@code @Tasklet.Spec}. */
    protected final String name() {
        return getClass().getAnnotation(Tasklet.Spec.class).name();
    }
}
