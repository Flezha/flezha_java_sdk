package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.operator.Operator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mirror of the Rust test app's {@code retry_steps}: the per-cell retry
 * ladder. Task RA commits durable state, forwards on a hybrid channel, and
 * compensates by emitting an undo record; {@code retry_flaky} fails its first
 * {@code FLEZHA_STEPS_RETRY_FAILS} attempts (0 by default — the env is what
 * each scenario arms) and then emits {@code attempt:<n>:<input>}. The ladder is
 * the default policy of the {@code mid} edge that feeds it: 3 attempts.
 */
public final class RetryOperator implements Operator {

    @Override
    public String factoryId() {
        return "retry_steps";
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
        c.input(String.class, "in")
                .into(RetryA.class)
                .sink("mid", s -> ("res:" + s).getBytes(StandardCharsets.UTF_8))
                .into(RetryFlaky.class)
                .sink("out");
    }

    /** Task RA: durable, forwards on the hybrid channel, compensates with an undo record. */
    @Tasklet.Spec(name = "retry_a")
    public static final class RetryA extends DurableStep {
        @Override
        public void compensate(List<String> completedOutput, Ctx<SeenState, String> ctx) throws TaskletException {
            ctx.emit("undone");
        }
    }

    /** Fails its first armed attempts, then reports which attempt succeeded. */
    @Tasklet.Spec(name = "retry_flaky")
    public static final class RetryFlaky extends NoStateStep {

        /**
         * Attempts made in this process — a retry re-runs the tasklet in place,
         * so an in-memory counter is the whole ladder's view.
         */
        private static final AtomicInteger ATTEMPTS = new AtomicInteger();

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            int attempt = ATTEMPTS.incrementAndGet();
            if (attempt <= TestEnv.longVar("FLEZHA_STEPS_RETRY_FAILS", 0)) {
                throw new TaskletException("flaky attempt " + attempt);
            }
            ctx.emit("attempt:" + attempt + ":" + input);
        }
    }
}
