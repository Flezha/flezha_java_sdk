package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/**
 * Mirror of the Rust test app's {@code failed_stage_bounded}: a failed stage
 * delivers nothing. The entry's two values reach one {@code failed_stage} Cell
 * in order: the first emits on a plugin channel, so the Cell's record carries
 * an output; the second fails, so the stage never commits and no marker ever
 * names that record.
 */
public final class FailedStageBoundedOperator implements BoundedOperator {

    @Override
    public String factoryId() {
        return "failed_stage_bounded";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in")
                .into(KeyedBoundedOperators.KeySplit.class)
                .into(FailedStage.class)
                .sink("mid", s -> ("lost:" + s).getBytes(StandardCharsets.UTF_8));
    }

    /** Emits the first value; fails on {@code b}. */
    @Tasklet.Spec(name = "failed_stage")
    public static final class FailedStage extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            if (input.equals("b")) {
                throw new TaskletException("failed_stage fails on b");
            }
            ctx.emit(input);
        }
    }
}
