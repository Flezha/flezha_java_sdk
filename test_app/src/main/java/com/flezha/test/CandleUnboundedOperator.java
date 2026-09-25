package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.UnboundedOperator;

/**
 * Mirror of the Rust test app's {@code CandleOperator}: the
 * stream-late-input-correction fixture — an event-time clock plus a declared
 * correction window, so a late trade rewinds the Cell and replays the
 * affected slice in event-time order.
 */
public final class CandleUnboundedOperator implements UnboundedOperator {

    @Override
    public String factoryId() {
        return "candle";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.effectivelyOnce();
        c.correctionWindow(600_000);
        c.input(String.class, "input").into(CandleTasklet.class).keyed().sink("sink");
    }
}
