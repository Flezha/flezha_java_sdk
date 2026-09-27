package com.flezha.sdk.channel;

import com.flezha.sdk.tasklet.Tasklet;
import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * A <b>merged</b> subscription under construction: one tasklet fed by two or
 * more source channels, each supplying a mapping from its payload into the
 * tasklet's declared {@code Input} sum type
 * ({@code multi-input-tasklets}).
 *
 * <pre>{@code
 * c.merge(SignalInput.class)
 *     .from(tickerStream, SignalInput.Ticker::new)
 *     .from(candleStream, SignalInput.Candle::new)
 *     .into(DetectorTasklet.class);
 * }</pre>
 *
 * <p>Sources may be entry channels (sealed payloads) or another tasklet's
 * output stream, freely mixed, on an unbounded operator and on a bounded operator
 * operator alike: every message a bounded operator receives carries the channel it
 * arrived on, so the mapping of that source is selected as it is on a
 * stream. On a bounded operator, a merged tasklet fed by an entry channel is a root
 * of that channel's chain, and a message on it runs the merged tasklet once,
 * on that message's mapping, in the pass the message starts.
 *
 * <p>{@code cellsFor} and the merge's own {@link #keyBy} are evaluated on
 * the <b>mapped</b> value, so per-source cell mode is encoded in the sum
 * type. Per-source FIFO order is preserved;
 * nothing is guaranteed across sources — correlate in the tasklet's own state.
 *
 * <p>The mappings MUST be pure: they run on live delivery and again on WAL
 * replay, and a replay that disagreed with the live run would restore a state
 * the live run never had. They must also be total — a mapping returns
 * {@code I}, not a result, so unlike an entry parser it has no error to hand
 * the channel's retry→DLQ ladder. Do the fallible work in the tasklet, on the
 * sum type.
 *
 * <p>A mapping that throws is contained everywhere it can be: on an
 * inter-tasklet source it is attributed to the producing tasklet
 * (stream) or turned into a terminal chain error the saga compensates
 * (bounded operator). The one unguarded seam is an unbounded operator's entry-channel source, where
 * the mapping runs on the wire-reader path — the same exposure the entry parser
 * there already carries. {@code docs/sdk/multi_input_tasklets.md} tabulates it.
 *
 * <p>Fewer than two sources, the same channel subscribed twice, a source whose
 * edge into this tasklet is already wired, or a merged type that is not the
 * consumer's {@code inputType()} all fail registration with a named error.
 *
 * <p>Nothing is recorded on the topology until {@code into}, so a merge left
 * unclosed declares no edges at all. Rust marks the equivalent builder
 * {@code #[must_use]} and the compiler warns; this module configures no
 * return-value lint (no ErrorProne/SpotBugs), so here it is a convention: every
 * {@code merge(...)} chain ends in an {@code into(...)}.
 */
public interface Merge<I> {

    /**
     * Add one source — an entry channel or another tasklet's output stream —
     * with the mapping that lifts its payload into the merged input. Any
     * transform declared on the source handle runs <b>before</b> that mapping,
     * on the source's own payload type.
     */
    @Nonnull
    <S> Merge<I> from(EdgeStream<S> source, Function<S, I> map);

    /**
     * Declare the Cell key for the merged tasklet — once, on the merge,
     * because by the time keying runs every source has been mapped into the
     * one input type. Declaring one on a <i>source</i> handle instead is
     * refused: the key would be handed the merged value, not that source's.
     */
    @Nonnull
    Merge<I> keyBy(Function<I, String> key);

    /**
     * Close the merge on its consumer and return <b>the consumer's</b> output
     * stream handle, so a merged node chains like any other.
     */
    @Nonnull
    <O> Stream<O> into(Class<? extends Tasklet<I, ?, O>> tasklet);

    /**
     * Like {@link #into(Class)}, but the consumer is a distinct named node —
     * see {@link Stream#into(Class, String)}.
     */
    @Nonnull
    <O> Stream<O> into(Class<? extends Tasklet<I, ?, O>> tasklet, String nodeName);
}
