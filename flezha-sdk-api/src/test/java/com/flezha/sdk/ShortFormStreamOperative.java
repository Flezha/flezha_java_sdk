package com.flezha.sdk;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;

/**
 * What the annotation processor generates from {@link RunnerLifecycleTest.ShortForm}, written by
 * hand because this module is built before the processor.
 */
public final class ShortFormStreamOperative implements StreamOperative {
    @Override
    public String factoryId() {
        return "lifecycle_short";
    }

    @Override
    public void channels(ChannelBuilder c) {}
}
