package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;
import com.flezha.sdk.procedure.Procedure;
import com.flezha.sdk.procedure.ProcedureCtx;
import com.flezha.sdk.procedure.SpawnException;
import com.flezha.sdk.procedure.SpawnHandle;
import com.flezha.sdk.procedure.SpawnOptions;
import com.flezha.sdk.procedure.SpawnResult;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.Stateless;
import com.flezha.sdk.state.WallClock;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Mirror of the Rust test app's {@code SpawnRequesterOperative}: on each input it spawns
 * {@code stateless_loop_workflow} once with no id and once under the input as its id, and prints
 * both results, because a handle's answer is observable nowhere but in the procedure that read it.
 */
public final class SpawnRequesterStreamOperative implements StreamOperative {

    @Override
    public String factoryId() {
        return "spawn_requester";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in").into(SpawnRequesterProcedure.class).sink("out");
    }

    public static final class SpawnRequesterProcedure
            implements Procedure<String, Stateless, String, NoChange, WallClock, Void> {

        private static final String WORKFLOW = "stateless_loop_workflow";

        @Override
        public String name() {
            return "spawn_requester";
        }

        @Override
        public Stateless initState(Optional<String> entityKey, Optional<RestoreInitPayload> initPayload) {
            return new Stateless();
        }

        @Override
        public void run(String input, StateHandle<Stateless, NoChange> state, ProcedureCtx ctx) {
            byte[] payload = input.getBytes(StandardCharsets.UTF_8);
            SpawnHandle unnamed = ctx.spawnWorkflow(WORKFLOW, payload);
            SpawnHandle named = ctx.spawnWorkflow(WORKFLOW, payload, SpawnOptions.id(input));
            print("unnamed", unnamed);
            print("named", named);
        }

        private static void print(String label, SpawnHandle handle) {
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
