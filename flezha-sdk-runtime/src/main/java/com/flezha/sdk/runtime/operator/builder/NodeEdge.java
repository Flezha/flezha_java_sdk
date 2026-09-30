package com.flezha.sdk.runtime.operator.builder;

import com.flezha.sdk.channel.CallTarget;
import com.flezha.sdk.channel.EdgeStream;
import com.flezha.sdk.channel.EntryChannel;
import com.flezha.sdk.channel.OutputChain;
import com.flezha.sdk.channel.Response;
import com.flezha.sdk.channel.Stream;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

/**
 * The producer side of the shared surface: its channel is the node's output
 * channel, created lazily. {@link StreamImpl} holds one of these rather than
 * extending it, because {@link Stream} must stay an abstract class (for
 * {@code @SafeVarargs branch}) and Java has no second superclass to spend.
 */
final class NodeEdge<T> extends EdgeStreamImpl<T> implements OutputChain<T> {

    private final int node;

    NodeEdge(UnboundedOperatorTopologyBuilder owner, int node, Integer pending, Class<?> payloadType) {
        super(
                owner,
                pending,
                payloadType != null ? payloadType : owner.tasklet(node).model.outputType());
        this.node = node;
    }

    @Override
    public int siteChannelId() {
        return owner.ensureOutputChannel(node);
    }

    @Override
    <U> EdgeStream<U> relink(Integer chain, Class<?> newPayloadType) {
        return new NodeEdge<U>(owner, node, chain, newPayloadType);
    }

    // The shared links, narrowed: relink answers a NodeEdge.

    @Override
    @Nonnull
    public <U> OutputChain<U> map(@Nonnull Class<U> type, @Nonnull Function<T, U> mapper) {
        return (OutputChain<U>) super.map(type, mapper);
    }

    @Override
    @Nonnull
    public <U> OutputChain<U> flatMap(@Nonnull Class<U> type, @Nonnull Function<T, ? extends Iterable<U>> mapper) {
        return (OutputChain<U>) super.flatMap(type, mapper);
    }

    @Override
    @Nonnull
    public OutputChain<T> filter(@Nonnull Predicate<T> predicate) {
        return (OutputChain<T>) super.filter(predicate);
    }

    @Override
    @Nonnull
    public OutputChain<T> keyBy(@Nonnull Function<T, String> key) {
        return (OutputChain<T>) super.keyBy(key);
    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public <A> EntryChannel<Response<T, A>> call(
            @Nonnull CallTarget target, @Nonnull Class<A> answer, long deadlineMs) {
        owner.checkMutable();
        return owner.attachCall(siteChannelId(), pending, target, deadlineMs, (Class<T>) payloadType, answer);
    }
}
