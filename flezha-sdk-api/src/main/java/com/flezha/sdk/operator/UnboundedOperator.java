package com.flezha.sdk.operator;

import com.flezha.sdk.channel.ChannelBuilder;

/**
 * Declares a graph of channels and tasklets that runs continuously over a stream of messages.
 *
 * <p>This is the operator kind to reach for when work is expressed as messages flowing through one
 * or more tasklets: readings arriving from a sensor, trades from a market feed, events from an
 * application. It runs until stopped, holds per-cell state between messages, and scales by
 * splitting that state across cells. For a graph that runs to completion and must not be
 * re-executed as a unit after a partial failure, committed stage by stage and resumed at the stage
 * it reached, use {@link BoundedOperator} instead.
 *
 * <p>An implementation declares two things and nothing else, its {@link #factoryId()} and its
 * graph in {@link #channels(ChannelBuilder)}:
 *
 * <pre>{@code
 * public final class WordCountOperator implements UnboundedOperator {
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
 * Flezha.createRunner("my_app").registerFactory(WordCountOperator.class).run();
 * }</pre>
 *
 * <p>Implementations must provide a public no-argument constructor. The runtime instantiates the
 * class once at registration in order to read its graph, and that instance is not the one that
 * handles messages.
 */
public interface UnboundedOperator extends Operator {

    /**
     * Whether per-cell state is persisted for this operator class. Defaults to {@code true}.
     *
     * <p>Override to {@code false} to opt out for the whole class: changes are still applied in
     * memory, so tasklets behave normally, but nothing is written to disk and a cell begins
     * with no history after a restart. This is a throughput trade for work whose state can be
     * recomputed or safely lost, and it is not the same as declaring
     * {@link com.flezha.sdk.state.NoChange}, which removes the change machinery from the tasklet
     * altogether.
     *
     * <p>The runtime announces the value to the controller, which resolves it for the class; an
     * user can still override it per class on the controller.
     */
    default boolean persistenceEnabled() {
        return true;
    }

    /**
     * Whether an idle instance sleeps. Defaults to {@code false}.
     *
     * <p>Override to {@code true} and an instance with nothing to do for {@code FLEZHA_PARK_IDLE_MS}
     * is evicted from its runner, holding no memory there, and wakes on its next message with its
     * cells restored. Its input is then kept durably by the controller, one log append per
     * message. A class that sleeps with {@link #persistenceEnabled()} off is refused at
     * registration as {@code SleepWithoutPersistence}.
     */
    default boolean sleepWhenIdle() {
        return false;
    }
}
