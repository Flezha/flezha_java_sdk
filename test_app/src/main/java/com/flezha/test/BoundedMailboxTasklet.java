package com.flezha.test;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Shared base for the three bounded-mailbox probes: a capacity-4 per-Cell
 * mailbox counting what it processed and echoing the input bytes. One concrete
 * tasklet per overflow policy, because the graph-shape rule allows exactly one
 * entry channel per relay; each declares {@link #CAPACITY} with its name.
 * Mirrors the Rust test app's {@code DropNothingTestTasklet} /
 * {@code DropNewestTestTasklet} / {@code DropOldestTestTasklet}.
 */
public abstract class BoundedMailboxTasklet implements Tasklet<String, BoundedMailboxTasklet.State, byte[]> {

    /** The per-Cell mailbox capacity every probe declares. */
    static final int CAPACITY = 4;

    public static final class State {
        public long processed;
    }

    public record Inc() implements Change<State> {
        @Override
        public void applyTo(State state) {
            state.processed += 1;
        }
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(State.class, Inc.class);
    }

    @Override
    public void run(String input, Ctx<State, byte[]> ctx) throws TaskletException {
        ctx.change(new Inc());
        ctx.emit(input.getBytes(StandardCharsets.UTF_8));
    }
}
