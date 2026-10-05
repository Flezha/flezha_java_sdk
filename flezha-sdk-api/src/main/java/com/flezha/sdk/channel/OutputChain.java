package com.flezha.sdk.channel;

import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

/**
 * A chain declared on a tasklet's output channel: the shared {@link EdgeStream} surface, plus the
 * {@link #call call} end only a producer's channel carries. An entry channel's chain has no such
 * end, as it has no output.
 */
public interface OutputChain<T> extends EdgeStream<T> {

    @Override
    @Nonnull
    <U> OutputChain<U> map(Class<U> type, Function<T, U> mapper);

    @Override
    @Nonnull
    <U> OutputChain<U> flatMap(Class<U> type, Function<T, ? extends Iterable<U>> mapper);

    @Override
    @Nonnull
    OutputChain<T> filter(Predicate<T> predicate);

    @Override
    @Nonnull
    OutputChain<T> keyBy(Function<T, String> key);

    /**
     * End this chain in a call to another relay: every value that reaches it, after this
     * chain's transforms and key, is one request, serialized as {@link Stream#output()} serializes
     * it. The call waits for nothing: its outcome arrives later as a {@link Response} on the entry
     * channel this answers, named {@code <this channel>.response}, which must feed a tasklet (the
     * one that made the call included) and decodes the answer as {@code input(answer, ...)} decodes.
     * The caller hears of the outcome within {@code deadlineMs} milliseconds of the emit,
     * {@code call.timeout} at the latest.
     *
     * <p>A call is a plugin end: a channel ends in one output, one response or one call, and a call
     * with a zero deadline, a Response channel nothing reads, or a {@code create(false)} factory
     * call with no {@link #keyBy} before it fail registration.
     */
    @Nonnull
    <A> EntryChannel<Response<T, A>> call(CallTarget target, Class<A> answer, long deadlineMs);
}
