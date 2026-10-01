package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/**
 * {@code keyed_block_steps} and {@code keyed_saga_steps}, the Java mirror of
 * the Rust test app's: the entry's comma-separated key list is split into one
 * value per key, and a {@code keyBy} on that value mints one Cell per key of a
 * durable compensable tasklet that declares no keys; each Cell commits one
 * idempotent change. The third tasklet blocks until the release env, or fails,
 * so the keyed stage is rolled back once per key.
 */
public final class KeyedStepsOperators {

    private KeyedStepsOperators() {}

    static Stream<String> keyedCommits(ChannelBuilder c) {
        return c.input(String.class, "in")
                .into(KeySplit.class)
                .named("keys")
                .keyBy(key -> key)
                .into(KeyedCommit.class)
                .named("committed");
    }

    public static final class KeyedBlock implements Operator {
        @Override
        public String factoryId() {
            return "keyed_block_steps";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public Durable durable() {
            return TestEnv.fixtureDurable();
        }

        @Override
        public void channels(ChannelBuilder c) {
            keyedCommits(c).into(CommitBlockOperator.BlockB.class);
        }
    }

    public static final class KeyedSaga implements Operator {
        @Override
        public String factoryId() {
            return "keyed_saga_steps";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public Durable durable() {
            return TestEnv.fixtureDurable();
        }

        @Override
        public void channels(ChannelBuilder c) {
            keyedCommits(c).into(KeyedFail.class);
        }
    }

    @Tasklet.Spec(name = "key_split")
    public static final class KeySplit extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            for (String key : input.split(",")) {
                ctx.emit(key);
            }
        }
    }

    @Tasklet.Spec(name = "keyed_commit")
    public static final class KeyedCommit extends DurableStep {
        @Override
        public void compensate(List<String> completedOutput, Ctx<SeenState, String> ctx) {
            // A no-op undo: the done marker it earns is what the scenarios read.
        }
    }

    @Tasklet.Spec(name = "keyed_fail")
    public static final class KeyedFail extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            throw new TaskletException("keyed_fail always fails");
        }
    }
}
