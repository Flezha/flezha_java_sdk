package com.flezha.sdk.relay;

import com.flezha.sdk.channel.ChannelBuilder;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javax.annotation.Nonnull;

/**
 * One relay class: a stable {@link #factoryId()}, the graph of channels and tasklets declared in
 * {@link #channels(ChannelBuilder)}, and the settings tree that says how it runs ({@link #run()})
 * and where its work is kept ({@link #durable()}).
 *
 * <p>Every "one of" of the tree is a sealed interface and every nested setting a member of its
 * parent only, so a setting written under a parent that does not carry it does not compile:
 * {@code each} exists only on {@link Run.Steps}, {@code ack} only on
 * {@link Exactly.EffectivelyOnce}, {@code sleepAfter} only on {@link Durable.Local} and
 * {@link Durable.Remote}.
 *
 * <pre>{@code
 * public final class WordCountRelay implements Relay {
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
 * <p>With nothing overridden the class runs as a flow, delivers at least once, and is kept on the
 * controller's disk without ever sleeping; a steps class kept there sleeps after six hours idle. A
 * class that runs in steps says so:
 *
 * <pre>{@code
 * @Override
 * public Run run() {
 *     return new Run.Steps(Each.PER_INPUT, OnFailure.KEEP_PROGRESS);
 * }
 * }</pre>
 *
 * <p>The class is declared on a runner with
 * {@link com.flezha.sdk.FlezhaRunner#registerFactory(Class)}. Implementations need a public
 * no-argument constructor: the runtime instantiates the class once at registration to read its
 * graph and settings, and that instance is not the one that handles messages.
 */
public interface Relay {

    /**
     * How long an idle durable steps instance waits before it sleeps when its class says nothing. A
     * flow instance whose class says nothing never sleeps.
     */
    Duration DEFAULT_SLEEP_AFTER = Duration.ofHours(6);

    /**
     * The stable, controller-facing identity of this relay class, and the name
     * {@code flz relay start <factory_id>} resolves. Exactly one factory id per class.
     *
     * <p>This value is written into durable state and into the controller's registry, so changing
     * it does not rename an existing relay: it declares a new one, and state recorded under the
     * previous identity is no longer associated with it.
     */
    @Nonnull
    String factoryId();

    /**
     * Declares the graph. Called exactly once per class, at registration, and the resulting graph
     * is shared by every instance the controller later starts.
     *
     * <p>Each call on the builder returns the next handle in the chain, so the whole graph is one
     * fluent expression, validated as a whole before any message is processed. An inconsistent
     * declaration is refused at registration naming the declaration at fault.
     *
     * <p>A class that runs in steps runs this graph in topological order: a tasklet becomes
     * runnable only after every producer feeding it has committed, and a fan-in node runs once,
     * with the accumulated batch. A cycle is refused at registration.
     */
    void channels(ChannelBuilder c);

    /** What the relay is for, shown by {@code flz relay info}. Empty by default. */
    @Nonnull
    default String description() {
        return "";
    }

    /**
     * A wall-clock stop, for either run: once it elapses the instance drains and ends
     * {@code cancelled}. Empty (the default) runs until the instance ends on its own or is stopped.
     */
    @Nonnull
    default Optional<Duration> maxDuration() {
        return Optional.empty();
    }

    /** How an instance runs. Defaults to {@link Run#flow()}. */
    @Nonnull
    default Run run() {
        return Run.flow();
    }

    /** Where an instance's work is kept. Defaults to {@link Durable#local()}, sleeping by the run. */
    @Nonnull
    default Durable durable() {
        return Durable.local();
    }

    /**
     * How an instance runs: its commit point is an input or a checkpoint ({@link Flow}), or a
     * stage of its graph ({@link Steps}).
     */
    sealed interface Run permits Run.Flow, Run.Steps {

        /** A flow delivering at least once. */
        static Run flow() {
            return new Flow(new Exactly.AtLeastOnce());
        }

        /** A steps run with every member at its default. */
        static Run steps() {
            return steps(Each.ONCE);
        }

        /** A steps run repeating as {@code each} says, undoing everything on failure. */
        static Run steps(Each each) {
            return new Steps(each, OnFailure.UNDO_ALL);
        }

        /**
         * Messages flow continuously through the graph, each cell holding its state between them.
         *
         * @param exactly the duplicate rule
         */
        record Flow(Exactly exactly) implements Run {
            public Flow {
                Objects.requireNonNull(exactly, "exactly");
            }
        }

        /**
         * The graph runs stage by stage, each stage committed at its boundary, and a crash resumes
         * at the stage that had not committed. The value a tasklet hands to the next stage is
         * written down there, encoded by a codec the runtime derives.
         *
         * @param each how the graph repeats
         * @param onFailure what an exhausted retry ladder undoes
         */
        record Steps(Each each, OnFailure onFailure) implements Run {
            public Steps {
                Objects.requireNonNull(each, "each");
                Objects.requireNonNull(onFailure, "onFailure");
            }
        }
    }

    /** A flow relay's duplicate rule. */
    sealed interface Exactly permits Exactly.AtLeastOnce, Exactly.EffectivelyOnce {

        /** A crash may replay an input whose outputs were already delivered. */
        record AtLeastOnce() implements Exactly {}

        /**
         * The effectively-once tier: each invocation's state changes, plugin outputs and consumed
         * input offset ship as one effect record the controller persists under one fsync. Every
         * plugin-bound channel must be declared {@code .keyed()}, because the tier's replay
         * re-emits outputs and only a keyed sink absorbs that as an idempotent upsert. The tier
         * needs a disk: with {@link Durable.Memory} it is refused as
         * {@code topology.effectively_once_without_persistence}.
         *
         * @param ack when a consumed input is acknowledged
         * @param correctionMs the late-input correction window; empty never corrects. An input
         *     whose event time falls behind the cell's high-water mark by less than the window
         *     rewinds the cell and replays the slice in event-time order. It needs an event-time
         *     clock and keyed channels.
         * @param beyondWindow what an input later than the window becomes
         */
        record EffectivelyOnce(Ack ack, OptionalLong correctionMs, BeyondWindow beyondWindow) implements Exactly {
            public EffectivelyOnce {
                Objects.requireNonNull(ack, "ack");
                Objects.requireNonNull(correctionMs, "correctionMs");
                Objects.requireNonNull(beyondWindow, "beyondWindow");
            }

            /** The tier with every member at its default. */
            public EffectivelyOnce() {
                this(new Ack.PerInput(), OptionalLong.empty(), BeyondWindow.PROCESS_AS_ARRIVAL);
            }

            public EffectivelyOnce withAck(Ack ack) {
                return new EffectivelyOnce(ack, correctionMs, beyondWindow);
            }

            public EffectivelyOnce withCorrectionMs(long windowMs) {
                return new EffectivelyOnce(ack, OptionalLong.of(windowMs), beyondWindow);
            }

            public EffectivelyOnce withBeyondWindow(BeyondWindow policy) {
                return new EffectivelyOnce(ack, correctionMs, policy);
            }
        }
    }

    /** When the effectively-once tier acknowledges a consumed input. */
    sealed interface Ack permits Ack.PerInput, Ack.Checkpoint {

        /** After every invocation. */
        record PerInput() implements Ack {}

        /**
         * Only at a checkpoint: an explicit {@code ctx.checkpoint()}, or the interval closing on
         * its own. The interval between checkpoints is the work a crash replays and the work the
         * controller's input log retains; in exchange a cell whose state is coherent only at a
         * boundary never persists a half-built view of it.
         *
         * @param every close the interval once this many inputs folded into it; empty waits for
         *     an explicit checkpoint
         * @param maxDelayMs close the interval this long after the input that opened it; required
         *     when outputs are held
         * @param outputs whether emits ship as produced or are held until the checkpoint
         */
        record Checkpoint(OptionalInt every, OptionalLong maxDelayMs, Outputs outputs) implements Ack {
            public Checkpoint {
                Objects.requireNonNull(every, "every");
                Objects.requireNonNull(maxDelayMs, "maxDelayMs");
                Objects.requireNonNull(outputs, "outputs");
            }

            /** Checkpoints taken only by {@code ctx.checkpoint()}, outputs shipped as produced. */
            public Checkpoint() {
                this(OptionalInt.empty(), OptionalLong.empty(), Outputs.AS_PRODUCED);
            }

            public Checkpoint withEvery(int inputs) {
                return new Checkpoint(OptionalInt.of(inputs), maxDelayMs, outputs);
            }

            public Checkpoint withMaxDelayMs(long delayMs) {
                return new Checkpoint(every, OptionalLong.of(delayMs), outputs);
            }

            public Checkpoint withOutputs(Outputs held) {
                return new Checkpoint(every, maxDelayMs, held);
            }
        }
    }

    /** Where a checkpointing relay's emits go before the checkpoint. */
    enum Outputs {
        /** Flushed as they happen: checkpointing moves the acknowledgement, not the output. */
        AS_PRODUCED,
        /**
         * Held with the interval's changes and shipped inside its checkpoint record, one WAL
         * append per interval. An emit is not visible downstream until its interval closes.
         */
        AT_CHECKPOINT
    }

    /** What an input later than the correction window becomes. */
    enum BeyondWindow {
        /** Processed as though it had just arrived. The default: a window never starts dropping data. */
        PROCESS_AS_ARRIVAL,
        /** Dropped. Choose this only when a stale result is worse than a missing one. */
        DROP
    }

    /** How a steps relay repeats. */
    enum Each {
        /** One pass over the first message, then done. The default. */
        ONCE,
        /** One iteration per message; the instance waits between iterations. */
        PER_INPUT,
        /**
         * Each iteration seeded by the previous iteration's terminal handoff, until an empty batch.
         * A durable chained loop needs its terminal tasklet to release an output channel, since a
         * resumed iteration re-seeds from that journaled handoff.
         */
        CHAINED
    }

    /** What a failed steps instance undoes. */
    enum OnFailure {
        /** Every completed tasklet is undone in reverse through its {@code compensate}. The default. */
        UNDO_ALL,
        /**
         * Finished iterations are kept and only the in-flight one is undone. In a
         * {@link Each#PER_INPUT} loop a failed turn is dead-lettered and the loop takes its next
         * message.
         */
        KEEP_PROGRESS
    }

    /** Where an instance's work is kept. */
    sealed interface Durable permits Durable.Memory, Durable.Local, Durable.Remote {

        /** {@link Local}, sleeping after the run's default. */
        static Durable local() {
            return new Local();
        }

        /** {@link Remote}, sleeping after the run's default. */
        static Durable remote() {
            return new Remote();
        }

        /** In memory only: changes apply, nothing is written, and a crash starts over. */
        record Memory() implements Durable {}

        /**
         * On the controller's disk.
         *
         * @param sleepAfter how long an idle instance waits before it is evicted from its runner,
         *     waking on its next message with its cells restored
         */
        record Local(SleepAfter sleepAfter) implements Durable {
            public Local {
                Objects.requireNonNull(sleepAfter, "sleepAfter");
            }

            public Local() {
                this(new SleepAfter.RunDefault());
            }

            public Local(Duration sleepAfter) {
                this(new SleepAfter.After(sleepAfter));
            }
        }

        /**
         * In the remote copy before the commit point is acknowledged, so a commit survives losing
         * the controller's disk, at one remote round trip per commit. A start on a controller
         * without a durability backend is refused.
         *
         * @param sleepAfter as {@link Local#sleepAfter()}
         */
        record Remote(SleepAfter sleepAfter) implements Durable {
            public Remote {
                Objects.requireNonNull(sleepAfter, "sleepAfter");
            }

            public Remote() {
                this(new SleepAfter.RunDefault());
            }

            public Remote(Duration sleepAfter) {
                this(new SleepAfter.After(sleepAfter));
            }
        }

        /** How long an idle durable instance waits before it sleeps. */
        sealed interface SleepAfter permits SleepAfter.RunDefault, SleepAfter.Never, SleepAfter.After {

            /**
             * The run's own: a flow instance never sleeps, a steps instance sleeps after {@link
             * Relay#DEFAULT_SLEEP_AFTER}. A sleeping flow instance has its input journaled before it is
             * forwarded, which a flow class pays only when it asks to sleep.
             */
            record RunDefault() implements SleepAfter {}

            /** Never sleeps. */
            record Never() implements SleepAfter {}

            /** @param interval how long the instance stays idle before it sleeps */
            record After(Duration interval) implements SleepAfter {
                public After {
                    Objects.requireNonNull(interval, "interval");
                }
            }
        }
    }
}
