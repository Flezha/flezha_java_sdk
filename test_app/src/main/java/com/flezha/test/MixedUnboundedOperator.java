package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.Stateless;
import com.flezha.sdk.state.WallClock;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code MixedOperator}: one {@code @Tasklet}
 * sugar tasklet ({@link MixedMacroEntry}) wired to one hand-written
 * {@code Tasklet} in an explicit {@code channels()} body. Declared explicitly
 * rather than collected, exactly as the Rust twin is.
 */
public final class MixedUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "mixed_operator";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input")
                .into(MixedMacroEntryTasklet.class)
                .named("hop")
                .into(MixedHandDownstream.class)
                .sink("kafka");
    }

    public static final class MixedHandDownstream
            implements Tasklet<String, Stateless, byte[], NoChange, WallClock, Void> {

        @Override
        public String name() {
            return "mixed_hand_downstream";
        }

        @Override
        public Stateless initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new Stateless();
        }

        @Override
        public void run(String input, StateHandle<Stateless, NoChange> state, TaskletCtx ctx)
                throws TaskletException {
            ctx.emit(input.getBytes(StandardCharsets.UTF_8));
        }
    }
}
