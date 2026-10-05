package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;

/**
 * Mirror of the Rust test app's {@code MixedRelay}: a top-level tasklet
 * ({@link MixedMacroEntry}) wired to a nested one in an explicit
 * {@code channels()} body. Declared explicitly rather than collected, exactly
 * as the Rust twin is.
 */
public final class MixedRelay implements Relay {

    @Override
    public String factoryId() {
        return "mixed_relay";
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
