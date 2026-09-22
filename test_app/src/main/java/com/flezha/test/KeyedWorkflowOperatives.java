package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.List;

/**
 * {@code keyed_block_workflow} and {@code keyed_saga_workflow}, the Java mirror of
 * the Rust test app's: the entry's comma-separated key list is split into one
 * value per key, and a {@code keyBy} on that value mints one Cell per key of a
 * durable compensable tasklet that declares no keys; each Cell commits one
 * idempotent change. The third tasklet blocks until the release env, or fails,
 * so the keyed stage is rolled back once per key.
 */
public final class KeyedWorkflowOperatives {

    private KeyedWorkflowOperatives() {}

    static Stream<String> keyedCommits(ChannelBuilder c) {
        return c.input(String.class, "in")
                .into(KeySplit.class)
                .named("keys")
                .keyBy(key -> key)
                .into(KeyedCommit.class)
                .named("committed");
    }

    public static final class KeyedBlock implements WorkflowOperative {
        @Override
        public String factoryId() {
            return "keyed_block_workflow";
        }

        @Override
        public void channels(ChannelBuilder c) {
            keyedCommits(c).into(CommitBlockWorkflowOperative.BlockB.class);
        }
    }

    public static final class KeyedSaga implements WorkflowOperative {
        @Override
        public String factoryId() {
            return "keyed_saga_workflow";
        }

        @Override
        public void channels(ChannelBuilder c) {
            keyedCommits(c).into(KeyedFail.class);
        }
    }

    public static final class KeySplit extends StatelessStep {
        @Override
        public String name() {
            return "key_split";
        }

        @Override
        public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            for (String key : input.split(",")) {
                ctx.emit(key);
            }
        }
    }

    public static final class KeyedCommit extends DurableStep {
        @Override
        public String name() {
            return "keyed_commit";
        }

        @Override
        public void compensate(List<String> completedOutput, StateHandle<SeenState, Insert> state, TaskletCtx ctx) {
            // A no-op undo: the done marker it earns is what the scenarios read.
        }
    }

    public static final class KeyedFail extends StatelessStep {
        @Override
        public String name() {
            return "keyed_fail";
        }

        @Override
        public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            throw new TaskletException("keyed_fail always fails");
        }
    }
}
