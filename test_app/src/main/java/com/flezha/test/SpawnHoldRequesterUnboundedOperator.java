package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import java.nio.charset.StandardCharsets;

/**
 * Mirror of the Rust test app's {@code SpawnHoldRequesterOperator}: an effectively-once stream whose
 * tasklet spawns {@code stateless_loop_bounded} with no id, prints the answer as
 * {@code spawn_result hold <id> <outcome>}, then blocks until the release env is set, so a runner
 * killed there dies with the spawn answered and its input not durably consumed.
 */
public final class SpawnHoldRequesterUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "spawn_hold_requester";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.effectivelyOnce();
        c.input(String.class, "in")
                .onFull(OverflowPolicy.DROP_NOTHING)
                .into(SpawnHoldRequesterTasklet.class)
                .keyed()
                .sink("out");
    }

    @Tasklet.Spec(name = "spawn_hold_requester")
    public static final class SpawnHoldRequesterTasklet implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) {
            SpawnRequesterUnboundedOperator.SpawnRequesterTasklet.print(
                    "hold", ctx.spawnBounded("stateless_loop_bounded", input.getBytes(StandardCharsets.UTF_8)));
            BoundedE2eGate.blockUntilReleased();
        }
    }
}
