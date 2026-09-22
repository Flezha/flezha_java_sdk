package com.flezha.sdk.operative;

import com.flezha.sdk.channel.ChannelBuilder;

/**
 * Declares a graph of channels and tasklets that runs continuously over a stream of messages.
 *
 * <p>This is the operative kind to reach for when work is expressed as messages flowing through one
 * or more tasklets: readings arriving from a sensor, trades from a market feed, events from an
 * application. It runs until stopped, holds per-cell state between messages, and scales by
 * splitting that state across cells. For a graph that runs to completion and must not be
 * re-executed as a unit after a partial failure, committed stage by stage and resumed at the stage
 * it reached, use {@link WorkflowOperative} instead.
 *
 * <p>An implementation declares two things and nothing else, its {@link #factoryId()} and its
 * graph in {@link #channels(ChannelBuilder)}:
 *
 * <pre>{@code
 * public final class WordCountOperative implements StreamOperative {
 *
 *     @Override
 *     public String factoryId() {
 *         return "word_count";
 *     }
 *
 *     @Override
 *     public void channels(ChannelBuilder c) {
 *         c.input(String.class, "lines")
 *                 .into(SplitTasklet.class)
 *                 .keyBy(word -> word)
 *                 .into(TallyTasklet.class)
 *                 .sink("totals");
 *     }
 * }
 * }</pre>
 *
 * <p>The class is then declared on a runner, which is what starts serving it:
 *
 * <pre>{@code
 * Flezha.createRunner("my_app").registerFactory(WordCountOperative.class).run();
 * }</pre>
 *
 * <p>Implementations must provide a public no-argument constructor. The runtime instantiates the
 * class once at registration in order to read its graph, and that instance is not the one that
 * handles messages.
 */
public interface StreamOperative extends Operative {

    /**
     * Whether per-cell state is persisted for this operative class. Defaults to {@code true}.
     *
     * <p>Override to {@code false} to opt out for the whole class: changes are still applied in
     * memory, so tasklets behave normally, but nothing is written to disk and a cell begins
     * with no history after a restart. This is a throughput trade for work whose state can be
     * recomputed or safely lost, and it is not the same as declaring
     * {@link com.flezha.sdk.state.NoChange}, which removes the change machinery from the tasklet
     * altogether.
     *
     * <p>An operator can override this per runner; the effective value is the runner's override
     * where present, otherwise this method's, otherwise {@code true}.
     */
    default boolean persistenceEnabled() {
        return true;
    }
}
