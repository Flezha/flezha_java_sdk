package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code reply_block_loop_steps}: the chat
 * loop with the block moved AFTER the root's commit. TURN commits its turn,
 * REPLY blocks on the turn {@code reply-crash} while the release env is unset,
 * so a SIGKILL lands between a root's {@code task_committed} and the pass's
 * {@code iter_committed}.
 */
public final class ReplyBlockLoopOperator implements Operator {
    /** The turn whose reply BLOCKS while the release env is unset. */
    static final String REPLY_CRASH_TURN = "reply-crash";

    @Override
    public String factoryId() {
        return "reply_block_loop_steps";
    }

    @Override
    public Run run() {
        return Run.steps(Each.PER_INPUT);
    }

    @Override
    public Durable durable() {
        return TestEnv.fixtureDurable();
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
    @Tasklet.Spec(name = "blocking_reply")
    public static final class BlockingReply extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            if (REPLY_CRASH_TURN.equals(input)) {
                StepsE2eGate.blockUntilReleased();
            }
            ctx.emit("reply:" + input);
        }
    }
}
