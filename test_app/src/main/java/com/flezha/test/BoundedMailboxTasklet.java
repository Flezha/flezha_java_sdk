package com.flezha.test;

import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Shared base for the three bounded-mailbox probes: a capacity-4 per-Cell
 * mailbox counting what it processed and echoing the input bytes. One concrete
 * tasklet per overflow policy, because the graph-shape rule allows exactly one
 * entry channel per operator. Mirrors the Rust test app's
 * {@code DropNothingTestTasklet} / {@code DropNewestTestTasklet} /
 * {@code DropOldestTestTasklet}.
 */
public abstract class BoundedMailboxTasklet
        implements Tasklet<
                String, BoundedMailboxTasklet.State, byte[], BoundedMailboxTasklet.Inc, WallClock, Void> {

    public static final class State {
        public long processed;
    }

    public record Inc() implements Change {}

    @Override
    public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return new State();
    }

    @Override
    public void applyChange(State state, Inc change) {
        state.processed += 1;
    }

    @Override
    public List<Class<?>> kryoTypes() {
        return List.of(State.class, Inc.class);
    }

    @Override
    public int mailboxCapacity() {
        return 4;
    }

    @Override
    public void run(String input, StateHandle<State, Inc> state, TaskletCtx ctx) throws TaskletException {
        state.apply(new Inc());
        ctx.emit(input.getBytes(StandardCharsets.UTF_8));
    }
}
