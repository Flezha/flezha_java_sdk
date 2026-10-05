package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/**
 * Mirror of the Rust test app's {@code effect_flow}: the {@code effect_block_steps} call in a flow
 * relay at the default tier, which keeps no input log, so nothing re-runs an input and nothing is
 * kept.
 */
public final class EffectFlowRelay implements Relay {

    @Override
    public String factoryId() {
        return "effect_flow";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in").into(EffectOnce.class);
    }

    @Tasklet.Spec(name = "effect_once")
    public static final class EffectOnce extends NoStateStep {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.effect("model", String.class, () -> EffectBlockRelay.countRun(input));
        }
    }
}
