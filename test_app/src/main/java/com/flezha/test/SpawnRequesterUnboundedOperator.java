package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.SpawnException;
import com.flezha.sdk.tasklet.SpawnHandle;
import com.flezha.sdk.tasklet.SpawnOptions;
import com.flezha.sdk.tasklet.SpawnResult;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.Stateless;
import com.flezha.sdk.state.WallClock;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code SpawnRequesterOperator}: on each input it spawns
 * {@code stateless_loop_bounded} once with no id and once under the input as its id, and prints
 * both results, because a handle's answer is observable nowhere but in the tasklet that read it.
 */
public final class SpawnRequesterUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "spawn_requester";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in").into(SpawnRequesterTasklet.class).sink("out");
    }

    public static final class SpawnRequesterTasklet
            implements Tasklet<String, Stateless, String, NoChange, WallClock, Void> {

        private static final String BOUNDED = "stateless_loop_bounded";

        @Override
        public String name() {
            return "spawn_requester";
        }

        @Override
        public Stateless initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
            return new Stateless();
        }

        @Override
        public void run(String input, StateHandle<Stateless, NoChange> state, TaskletCtx ctx) {
            byte[] payload = input.getBytes(StandardCharsets.UTF_8);
            SpawnHandle unnamed = ctx.spawnBounded(BOUNDED, payload);
            SpawnHandle named = ctx.spawnBounded(BOUNDED, payload, SpawnOptions.id(input));
            print("unnamed", unnamed);
            print("named", named);
        }

        /** The handle's answer on stdout as {@code spawn_result <label> <id> <outcome>}. */
        static void print(String label, SpawnHandle handle) {
            String answer;
            try {
                SpawnResult result = handle.await();
                answer = result.id() + " " + result.outcome().name().toLowerCase(Locale.ROOT);
            } catch (SpawnException e) {
                answer = "error " + e.getMessage();
            }
            System.out.println("spawn_result " + label + " " + answer);
            System.out.flush();
        }
    }
}
