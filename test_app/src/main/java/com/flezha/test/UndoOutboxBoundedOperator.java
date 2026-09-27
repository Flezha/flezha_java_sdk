package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.operator.BoundedOperator;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Mirror of the Rust test app's {@code undo_outbox_bounded}: a compensation's
 * undo output. Task UA commits, emits its forward output on a hybrid plugin
 * channel, and declares a {@code compensate} hook that EMITS an undo record,
 * which rides UA's Cell's own record and is released once the
 * {@code compensated} entry naming it is durable. Task C fails immediately, so
 * the chain always rolls back; the hook BLOCKS until the release env is set, so
 * on the first runner the SIGKILL lands before the undo was written.
 */
public final class UndoOutboxBoundedOperator implements BoundedOperator {

    @Override
    public String factoryId() {
        return "undo_outbox_bounded";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(UndoOutboxA.class)
                .sink("mid", s -> ("out:" + s).getBytes(StandardCharsets.UTF_8))
                .into(UndoC.class);
    }

    /** Task UA: durable + compensable; the hook blocks, then emits the undo record. */
    @Tasklet.Spec(name = "undo_outbox_a")
    public static final class UndoOutboxA extends DurableStep {
        @Override
        public void compensate(List<String> completedOutput, Ctx<SeenState, String> ctx) throws TaskletException {
            BoundedE2eGate.blockUntilReleased();
            ctx.emit("undone");
        }
    }

    /** Task C: fails immediately, so the chain always rolls back. */
    @Tasklet.Spec(name = "undo_outbox_c")
    public static final class UndoC extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            throw new TaskletException("undo_outbox_c always fails");
        }
    }
}
