package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/**
 * Mirror of the Rust test app's {@code MixedOperator}: a top-level tasklet
 * ({@link MixedMacroEntry}) wired to a nested one in an explicit
 * {@code channels()} body. Declared explicitly rather than collected, exactly
 * as the Rust twin is.
 */
public final class MixedOperator implements Operator {

    @Override
    public String factoryId() {
        return "mixed_operator";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(MixedMacroEntry.class)
                .named("hop")
                .into(MixedHandDownstream.class)
                .sink("kafka");
    }

    @Tasklet.Spec(name = "mixed_hand_downstream")
    public static final class MixedHandDownstream implements Tasklet<String, Void, byte[]> {

        @Override
        public void run(String input, Ctx<Void, byte[]> ctx) throws TaskletException {
            ctx.emit(input.getBytes(StandardCharsets.UTF_8));
        }
    }
}
