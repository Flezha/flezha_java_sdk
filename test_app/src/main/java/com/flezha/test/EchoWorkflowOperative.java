package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;

/**
 * Run-once workflow operative mirroring {@code rust_runner}'s
 * {@code echo_workflow}: its one tasklet emits {@code echo:<message>} to the
 * sink once for the first message the instance receives, the pass ends, and
 * the controller deregisters it.
 */
public final class EchoWorkflowOperative implements WorkflowOperative {

    @Override
    public String factoryId() {
        return "echo_workflow";
    }

    @Override
    public void tasklets(ChannelBuilder c) {
        c.input(String.class, "in").into(Echo.class).sink("out");
    }

    /** Echoes the message a {@code ONCE} workflow's single pass runs over. */
    public static final class Echo extends StatelessStep {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public void run(String args, StateHandle<State, NoChange> state, TaskletCtx ctx) throws TaskletException {
            ctx.emit("echo:" + args);
        }
    }
}
