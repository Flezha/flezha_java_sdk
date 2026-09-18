package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.procedure.ProcedureCtx;
import com.flezha.sdk.procedure.ProcedureException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;

/**
 * Run-once workflow operative mirroring {@code rust_runner}'s
 * {@code echo_workflow}: its one procedure emits {@code echo:<args>} to the sink
 * once, the pass ends, and the controller deregisters it.
 */
public final class EchoWorkflowOperative implements WorkflowOperative {

    @Override
    public String factoryId() {
        return "echo_workflow";
    }

    @Override
    public void procedures(ChannelBuilder c) {
        c.input(String.class, "in").into(Echo.class).sink("out");
    }

    /** Echoes the spawn args, which a {@code ONCE} workflow's single pass is seeded with. */
    public static final class Echo extends StatelessStep {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public void run(String args, StateHandle<State, NoChange> state, ProcedureCtx ctx) throws ProcedureException {
            ctx.emit("echo:" + args);
        }
    }
}
