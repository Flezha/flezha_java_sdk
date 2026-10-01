package com.flezha.examples.spawn;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operator.Operator;
import javax.annotation.Nonnull;

/** One instance, running forever over the event stream. */
public final class AlertDetectorOperator implements Operator {

    @Override
    @Nonnull
    public String factoryId() {
        return "alert_detector";
    }

    @Override
    public void channels(@Nonnull ChannelBuilder c) {
        c.input(String.class, "events").into(Detect.class).sink("alerts");
    }
}
