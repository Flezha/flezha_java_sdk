package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.LoopMode;
import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code reply_block_loop_workflow}: the chat
 * loop with the block moved AFTER the root's commit. TURN commits its turn,
 * REPLY blocks on the turn {@code reply-crash} while the release env is unset,
 * so a SIGKILL lands between a root's {@code task_committed} and the pass's
 * {@code iter_committed}.
 */
public final class ReplyBlockLoopWorkflowOperative implements WorkflowOperative {
    /** The turn whose reply BLOCKS while the release env is unset. */
    static final String REPLY_CRASH_TURN = "reply-crash";

    @Override
    public String factoryId() {
        return "reply_block_loop_workflow";
    }

    @Override
    public LoopMode loopMode() {
        return LoopMode.PER_INPUT;
    }

    @Override
    public boolean selfTerminating() {
        return false;
    }

    /** The duration backstop keeps a stray e2e instance from outliving the test run. */
    @Override
    public Optional<Duration> maxDuration() {
        return Optional.of(Duration.ofSeconds(600));
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(ChatSteps.Turn.class)
                .named("mid")
                .into(BlockingReply.class)
                .sink("out");
    }

    /** Blocks on the crash turn, then emits {@code reply:<input>} as the plain reply does. */
    public static final class BlockingReply extends StatelessStep {
        @Override
        public String name() {
            return "blocking_reply";
        }

        @Override
        public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            if (REPLY_CRASH_TURN.equals(input)) {
                WorkflowE2eGate.blockUntilReleased();
            }
            ctx.emit("reply:" + input);
        }
    }
}
