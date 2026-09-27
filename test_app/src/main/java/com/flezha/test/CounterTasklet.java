package com.flezha.test;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/**
 * Java equivalent of the Rust SDK's {@code CounterTasklet}. Each input is a
 * {@code Long} delta; the Tasklet records a {@link CounterChange}, which adds
 * it to {@code state.value}.
 */
@Tasklet.Spec(name = "counter_tasklet")
public class CounterTasklet implements Tasklet<Long, CounterTasklet.State, Void> {

    public static final class State {
        public long value;
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(State.class, CounterChange.class);
    }

    @Override
    public void run(Long input, Ctx<State, Void> ctx) throws TaskletException {
        ctx.change(new CounterChange(input));
    }
}
