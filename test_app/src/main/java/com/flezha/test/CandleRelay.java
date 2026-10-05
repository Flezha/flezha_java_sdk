package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;

/**
 * Mirror of the Rust test app's {@code CandleRelay}: the
 * stream-late-input-correction fixture — an event-time clock plus a declared
 * correction window, so a late trade rewinds the Cell and replays the
 * affected slice in event-time order.
 */
public final class CandleRelay implements Relay {

    @Override
    public String factoryId() {
        return "candle";
    }

    @Override
    public Run run() {
        return new Run.Flow(new Exactly.EffectivelyOnce().withCorrectionMs(600_000));
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "input").into(CandleTasklet.class).keyed().sink("sink");
    }
}
