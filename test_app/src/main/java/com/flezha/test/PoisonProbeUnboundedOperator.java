package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;

/**
 * Mirror of the Rust test app's {@code PoisonProbeOperator}: tier-off
 * retry-then-park probe for the dead-letter-queue e2e.
 */
public final class PoisonProbeUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "poison_probe";
    }

    @Override
    public void channels(ChannelBuilder c) {
        var sPoisonProbeTasklet = c.input(String.class, "input").retryBackoffMs(25).into(PoisonProbeTasklet.class);
        sPoisonProbeTasklet.sink("out");
    }
}
