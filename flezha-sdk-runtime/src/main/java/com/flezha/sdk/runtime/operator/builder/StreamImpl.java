package com.flezha.sdk.runtime.operator.builder;

import com.flezha.sdk.channel.CallTarget;
import com.flezha.sdk.channel.Embedding;
import com.flezha.sdk.channel.EntryChannel;
import com.flezha.sdk.channel.OutputChain;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.channel.PlainPayload;
import com.flezha.sdk.channel.Response;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.runtime.core.HostLink;
import com.flezha.sdk.runtime.operator.builder.OperatorTopologyBuilder.ChannelEntry;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The tasklet's output stream handle: a node id into the enclosing builder.
 * The channel out of the node is created lazily on first use.
 */
final class StreamImpl<T> extends Stream<T> implements EdgeSite {

    private final OperatorTopologyBuilder owner;
    private final int node;

    /**
     * The shared surface, held rather than inherited: {@link Stream} is an
     * abstract class (so {@code branch} can be {@code @SafeVarargs}) and
     * Java has no second superclass to spend, so the producer-side handle
     * forwards to the one implementation instead of re-stating it.
     */
    private final NodeEdge<T> edge;

    StreamImpl(OperatorTopologyBuilder owner, int node) {
        this(owner, node, null, null);
    }

    StreamImpl(OperatorTopologyBuilder owner, int node, Integer pending, Class<?> payloadType) {
        this.owner = owner;
        this.node = node;
        this.edge = new NodeEdge<>(owner, node, pending, payloadType);
    }

    private int channelId() {
        return owner.ensureOutputChannel(node);
    }

    @Override
    public int siteChannelId() {
        return channelId();
    }

    @Override
    public Integer sitePending() {
        return edge.pending;
    }

    @Override
    @Nonnull
    public <O> Stream<O> into(@Nonnull Class<? extends Tasklet<T, ?, O>> taskletClass) {
        return edge.into(taskletClass);
    }

    @Override
    @Nonnull
    public <O> Stream<O> into(@Nonnull Class<? extends Tasklet<T, ?, O>> taskletClass, @Nonnull String nodeName) {
        return edge.into(taskletClass, nodeName);
    }

    @Override
    @Nonnull
    public <O> Stream<O> into(
            @Nonnull Class<? extends Operator> operator, @Nonnull Class<O> output, @Nonnull Embedding embedding) {
        return edge.into(operator, output, embedding);
    }

    @Override
    @Nonnull
    public <U> OutputChain<U> map(@Nonnull Class<U> type, @Nonnull Function<T, U> mapper) {
        return edge.map(type, mapper);
    }

    @Override
    @Nonnull
    public <U> OutputChain<U> flatMap(@Nonnull Class<U> type, @Nonnull Function<T, ? extends Iterable<U>> mapper) {
        return edge.flatMap(type, mapper);
    }

    @Override
    @Nonnull
    public OutputChain<T> filter(@Nonnull Predicate<T> predicate) {
        return edge.filter(predicate);
    }

    @Override
    @Nonnull
    public OutputChain<T> keyBy(@Nonnull Function<T, String> key) {
        return edge.keyBy(key);
    }

    @Override
    @Nonnull
    public <A> EntryChannel<Response<T, A>> call(
            @Nonnull CallTarget target, @Nonnull Class<A> answer, long deadlineMs) {
        return edge.call(target, answer, deadlineMs);
    }

    @Override
    @Nonnull
    protected Stream<T> branchArms(@Nonnull List<Consumer<Stream<T>>> arms) {
        owner.checkMutable();
        owner.closeChain(channelId(), null, HostLink.branch(arms.size()));
        for (Consumer<Stream<T>> arm : arms) {
            arm.accept(this);
        }
        return this;
    }

    @Override
    @Nonnull
    public Stream<T> named(@Nonnull String name) {
        owner.checkMutable();
        // Recorded as its own chain, and applied to the builder's derived
        // default so this operator's own diagnostics name the channel the
        // author does. The core reads the link, for EVERY terminator (KI-120).
        owner.closeChain(channelId(), null, HostLink.named(name));
        owner.channel(channelId()).name = name;
        return this;
    }

    @Override
    @Nonnull
    public Stream<T> keyed() {
        owner.checkMutable();
        owner.channel(channelId()).keyed = true;
        return this;
    }

    @Override
    @Nonnull
    public Stream<T> onFull(@Nonnull OverflowPolicy policy) {
        edge.onFull(policy);
        return this;
    }

    @Override
    @Nonnull
    public Stream<T> retryMax(int maxAttempts) {
        edge.retryMax(maxAttempts);
        return this;
    }

    @Override
    @Nonnull
    public Stream<T> retryBackoffMs(long baseMs) {
        edge.retryBackoffMs(baseMs);
        return this;
    }

    @Override
    @Nonnull
    public Stream<T> sink(@Nonnull String channel, @Nonnull Function<T, byte[]> serializer) {
        return attachSink(channel, serializer);
    }

    @Override
    @Nonnull
    public Stream<T> sink(@Nonnull String channel) {
        return attachSink(channel, PlainPayload::encode);
    }

    @Override
    @Nonnull
    public Stream<T> sink(@Nonnull Function<T, byte[]> serializer) {
        return attachSink(null, serializer);
    }

    @Override
    @Nonnull
    public Stream<T> sink() {
        return attachSink(null, PlainPayload::encode);
    }

    @Override
    @Nonnull
    public Stream<T> response(@Nonnull Function<T, byte[]> serializer) {
        return attachPluginEnd(HostLink.response(), null, serializer);
    }

    @Override
    @Nonnull
    public Stream<T> response() {
        return attachPluginEnd(HostLink.response(), null, PlainPayload::encode);
    }

    private Stream<T> attachSink(@Nullable String channel, Function<T, byte[]> serializer) {
        return attachPluginEnd(HostLink.sink(channel), channel, serializer);
    }

    /** Record a plugin end, a sink or a response; both leave as a plugin output. */
    private Stream<T> attachPluginEnd(HostLink end, @Nullable String channel, Function<T, byte[]> serializer) {
        owner.checkMutable();
        ChannelEntry entry = owner.channel(channelId());
        owner.closeChain(channelId(), null, end);
        entry.response |= end.link().equals("response");
        if (channel != null) {
            // The record is what the core reads; this keeps the builder's own
            // diagnostics naming the channel the author does.
            entry.name = channel;
        }
        // First call wins on the builder's own side; a second end is recorded
        // and the core refuses the class, so nothing is registered either way.
        if (entry.pluginSinkSerializer == null) {
            entry.pluginSinkSerializer = value -> serializer.apply(cast(value));
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    private T cast(Object value) {
        return (T) value;
    }
}
