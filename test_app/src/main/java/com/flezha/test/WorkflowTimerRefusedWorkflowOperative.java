package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;

/**
 * A workflow whose one tasklet registers a processing-time timer: the context of a tasklet
 * inside a workflow carries no timer session, so the call throws {@code TimersUnavailable}, the
 * tasklet fails, and the instance ends with an error naming it.
 */
public final class WorkflowTimerRefusedWorkflowOperative implements WorkflowOperative {

    @Override
    public String factoryId() {
        return "workflow_timer_refused";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input").into(ArmTimer.class);
    }

    /** Registers a timer a minute ahead and emits nothing. */
    public static final class ArmTimer extends StatelessStep {

        @Override
        public String name() {
            return "workflow_timer_refused";
        }

        @Override
        public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx) throws TaskletException {
            ctx.timerAfter(Duration.ofSeconds(60));
        }
    }
}
