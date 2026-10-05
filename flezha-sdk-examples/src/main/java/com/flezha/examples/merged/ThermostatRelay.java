package com.flezha.examples.merged;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import javax.annotation.Nonnull;

/** Two entry channels, one consumer. Either source could equally be another tasklet's output. */
public final class ThermostatRelay implements Relay {

    @Override
    @Nonnull
    public String factoryId() {
        return "thermostat";
    }

    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        c.merge(DetectorInput.class)
                .from(c.input(Reading.class, "readings"), DetectorInput::ofReading)
                .from(c.input(Threshold.class, "config"), DetectorInput::ofThreshold)
                .into(Detector.class)
                .output("alerts");
    }
}
