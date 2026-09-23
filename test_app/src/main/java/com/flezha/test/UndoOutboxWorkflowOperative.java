package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.operative.WorkflowOperative;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Mirror of the Rust test app's {@code undo_outbox_workflow}: a compensation's
 * undo output. Task UA commits, emits its forward output on a hybrid plugin
 * channel, and declares a {@code compensate} hook that EMITS an undo record,
 * which rides UA's Cell's own record and is released once the
 * {@code compensated} entry naming it is durable. Task C fails immediately, so
 * the chain always rolls back; the hook BLOCKS until the release env is set, so
 * on the first runner the SIGKILL lands before the undo was written.
 */
public final class UndoOutboxWorkflowOperative implements WorkflowOperative {

    @Override
    public String factoryId() {
        return "undo_outbox_workflow";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(UndoOutboxA.class)
                .sink("mid", s -> ("out:" + s).getBytes(StandardCharsets.UTF_8))
                .into(UndoC.class);
    }

    /** Task UA: durable + compensable; the hook blocks, then emits the undo record. */
    public static final class UndoOutboxA extends DurableStep {

        @Override
        public String name() {
            return "undo_outbox_a";
        }

        @Override
        public void compensate(List<String> completedOutput, StateHandle<SeenState, Insert> state, TaskletCtx ctx)
                throws TaskletException {
            WorkflowE2eGate.blockUntilReleased();
            ctx.emit("undone");
        }
    }

    /** Task C: fails immediately, so the chain always rolls back. */
    public static final class UndoC extends StatelessStep {

        @Override
        public String name() {
            return "undo_outbox_c";
        }

        @Override
        public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            throw new TaskletException("undo_outbox_c always fails");
        }
    }
}
