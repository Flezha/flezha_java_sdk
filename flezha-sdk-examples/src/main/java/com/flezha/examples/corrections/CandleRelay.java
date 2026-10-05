package com.flezha.examples.corrections;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import javax.annotation.Nonnull;

/** Accepts corrections up to five minutes late; anything older is treated as a new arrival. */
public final class CandleRelay implements Relay {

    private static final long FIVE_MINUTES_MS = 5 * 60_000L;

    @Override
    @Nonnull
    public String factoryId() {
        return "late_candles";
    }

    @Override
    public Run run() {
        return new Run.Flow(new Exactly.EffectivelyOnce()
                .withCorrectionMs(FIVE_MINUTES_MS)
                .withBeyondWindow(BeyondWindow.PROCESS_AS_ARRIVAL));
    }

    @Override
    public void channels(@Nonnull ChannelBuilder c) {

        c.input(String.class, "trades").into(CandleTasklet.class).keyed().output("candles");
    }
}
