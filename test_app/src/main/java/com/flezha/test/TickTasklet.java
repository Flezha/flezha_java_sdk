package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/** Tasklet backing {@link CoalescingCounterUnboundedOperator}: folds one {@link TickInput}. */
@Tasklet.Spec(name = "tick_tasklet")
public final class TickTasklet implements Tasklet<TickInput, TickTasklet.State, Void> {

    public static final class State {
        public long last;
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(State.class, TickChange.class);
    }

    @Override
    public void run(TickInput input, Ctx<State, Void> ctx) throws TaskletException {
        ctx.change(new TickChange(input.interval(), input.ts(), input.delta()));
    }
}
