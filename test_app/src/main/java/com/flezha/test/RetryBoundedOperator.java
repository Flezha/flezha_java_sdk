package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.operator.BoundedOperator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mirror of the Rust test app's {@code retry_bounded}: the per-cell retry
 * ladder. Task RA commits durable state, forwards on a hybrid channel, and
 * compensates by emitting an undo record; {@code retry_flaky} fails its first
 * {@code FLEZHA_BOUNDED_RETRY_FAILS} attempts (0 by default — the env is what
 * each scenario arms) and then emits {@code attempt:<n>:<input>}. The ladder is
 * the default policy of the {@code mid} edge that feeds it: 3 attempts.
 */
public final class RetryBoundedOperator implements BoundedOperator {

    @Override
    public String factoryId() {
        return "retry_bounded";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(RetryA.class)
                .sink("mid", s -> ("res:" + s).getBytes(StandardCharsets.UTF_8))
                .into(RetryFlaky.class)
                .sink("out");
    }

    /** Task RA: durable, forwards on the hybrid channel, compensates with an undo record. */
    public static final class RetryA extends DurableStep {

        @Override
        public String name() {
            return "retry_a";
        }

        @Override
        public void compensate(List<String> completedOutput, StateHandle<SeenState, Insert> state, TaskletCtx ctx)
                throws TaskletException {
            ctx.emit("undone");
        }
    }

    /** Fails its first armed attempts, then reports which attempt succeeded. */
    public static final class RetryFlaky extends StatelessStep {

        /**
         * Attempts made in this process — a retry re-runs the tasklet in place,
         * so an in-memory counter is the whole ladder's view.
         */
        private static final AtomicInteger ATTEMPTS = new AtomicInteger();

        @Override
        public String name() {
            return "retry_flaky";
        }

        @Override
        public void run(String input, StateHandle<State, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            int attempt = ATTEMPTS.incrementAndGet();
            if (attempt <= TestEnv.longVar("FLEZHA_BOUNDED_RETRY_FAILS", 0)) {
                throw new TaskletException("flaky attempt " + attempt);
            }
            ctx.emit("attempt:" + attempt + ":" + input);
        }
    }
}
