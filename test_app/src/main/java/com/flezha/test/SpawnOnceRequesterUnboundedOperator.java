package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.Stateless;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.tasklet.SpawnHandle;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code spawn_once_requester}: on each input it spawns
 * {@code typed_once_bounded} once with no id, carrying the input as the JSON ticket that
 * bounded operator's entry channel decodes, and prints the handle's result, because a handle's answer
 * is observable nowhere but in the tasklet that read it.
 */
public final class SpawnOnceRequesterUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "spawn_once_requester";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in").into(SpawnOnceRequesterTasklet.class).sink("out");
    }

    public static final class SpawnOnceRequesterTasklet
            implements Tasklet<String, Stateless, String, NoChange, WallClock, Void> {

        @Override
        public String name() {
            return "spawn_once_requester";
        }

        @Override
        public Stateless initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new Stateless();
        }

        @Override
        public void run(String input, StateHandle<Stateless, NoChange> state, TaskletCtx ctx) {
            byte[] ticket = ("{\"text\":\"" + escape(input) + "\"}").getBytes(StandardCharsets.UTF_8);
            SpawnHandle handle = ctx.spawnBounded(TypedOnceBoundedOperator.FACTORY_ID, ticket);
            SpawnRequesterUnboundedOperator.SpawnRequesterTasklet.print("once", handle);
        }

        private static String escape(String text) {
            return text.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
