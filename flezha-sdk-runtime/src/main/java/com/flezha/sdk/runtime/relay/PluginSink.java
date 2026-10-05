package com.flezha.sdk.runtime.relay;

import java.util.Optional;
import java.util.function.Function;

/**
 * Plugin output attached to a channel via {@code output(...)} — the one
 * representation of plugin egress. A channel that also has consumers keeps
 * {@link com.flezha.sdk.channel.ChannelKind#INTER_TASKLET}; one with none is
 * the {@link com.flezha.sdk.channel.ChannelKind#PLUGIN_BOUND} terminal, a kind
 * the builder derives from the wiring rather than from which method was called.
 *
 * <p>A {@code call(...)} end is one too: {@code call} holds its chain's
 * transform and key, applied to each value as an edge's are before it is
 * serialized as a request. Its {@link ConsumerEdge#tasklet()} names none.
 */
public record PluginSink(Function<Object, byte[]> serializer, Optional<ConsumerEdge> call) {

    /** An output. */
    public PluginSink(Function<Object, byte[]> serializer) {
        this(serializer, Optional.empty());
    }
}
