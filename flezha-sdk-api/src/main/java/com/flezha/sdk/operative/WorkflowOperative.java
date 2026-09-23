package com.flezha.sdk.operative;

import com.flezha.sdk.channel.ChannelBuilder;
import java.time.Duration;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Declares a graph of tasklets that runs to completion, committed stage by stage, and resumes at
 * the stage it reached.
 *
 * <p>This is the operative kind for a job that must not be re-executed as a unit after a partial
 * failure: reserve stock, charge the card, dispatch the parcel. Once the card is charged,
 * restarting from the beginning is not a correct recovery, so each stage of the graph is committed
 * at its boundary and a crash resumes at the stage that had not committed. For work expressed as
 * messages flowing continuously through tasklets, use {@link StreamOperative} instead; this is the
 * heavier mechanism and costs a journal write per stage.
 *
 * <p>A workflow is started with its tasklets' initial state, runs on the messages that arrive on
 * its entry channels until an exit trigger, and is then torn down. It reuses the same building
 * blocks a stream operative uses (tasklets, channels, cells, per-cell state, {@code ctx.emit})
 * under a different lifecycle:
 *
 * <ul>
 *   <li><strong>Graph</strong>: {@link #channels} authors Tasklets/Channels/Cells on the same
 *       {@link ChannelBuilder} a stream operative uses; the workflow driver runs the static DAG in
 *       topological order with a per-tasklet count-based barrier (cells within one tasklet still
 *       run in parallel); a message runs only the chain of the entry channel it arrived on,
 *       fan-out branches are independent, and a fan-in node runs once, after every producer
 *       feeding it in that chain committed, with the accumulated batch. A cycle is refused at
 *       registration, and so is a graph that declares no tasklet.</li>
 *   <li><strong>Retry</strong>: a failing cell is retried under the policy of the edge that feeds
 *       its tasklet, {@code retryMax} / {@code retryBackoffMs} on the builder, exactly as a stream
 *       tunes it. Only the failing cell re-runs; its siblings stay buffered at the barrier, and a
 *       failed attempt's staged output is dropped rather than double-emitted.</li>
 *   <li><strong>Exit triggers</strong>: the operative terminates on the first
 *       of: the chain running to quiescence, a tasklet calling
 *       {@link com.flezha.sdk.tasklet.TaskletCtx#complete(byte[])}, a chained
 *       loop releasing an empty batch, a declared {@link #maxDuration}, or
 *       external {@code flz operative stop}. Every trigger converges on the
 *       single {@code WorkflowCompleted} teardown.</li>
 *   <li><strong>Inbox</strong>: every loop mode takes its input as messages;
 *       {@link LoopMode#ONCE} runs one pass on its first message, a
 *       {@link LoopMode#PER_INPUT} loop one iteration per message, and a durable
 *       instance parks while it waits for one.</li>
 * </ul>
 *
 * <p>A workflow declares its graph in {@link #channels}, on the same builder a stream operative
 * uses, and is declared with {@link com.flezha.sdk.FlezhaRunner#registerFactory(Class)}. The value
 * a tasklet hands to the next stage is written down at the stage boundary, which is what allows a
 * resumed workflow to pick up there; the runtime derives how to write it, so the graph declares
 * nothing about it:
 *
 * <pre>{@code
 * @Override
 * public void channels(ChannelBuilder c) {
 *     c.input(String.class, "order")
 *             .into(Reserve.class)
 *             .named("reserved")
 *             .into(Charge.class)
 *             .named("charged")
 *             .into(Ship.class)
 *             .sink("orders_sink");
 * }
 * }</pre>
 *
 * <p>A workflow carries none of a stream's delivery settings: the effectively-once tier, the
 * checkpoint settings and the correction window are refused at registration by name
 * ({@code EffectivelyOnceOnWorkflow}, {@code CheckpointOnWorkflow},
 * {@code CorrectionWindowOnWorkflow}), because a durable workflow already commits stage by stage.
 * A tasklet running inside a workflow registers timers as a stream tasklet does. Its firing is a
 * trigger of the run: it opens a pass at that tasklet, joins the tasklet's stage while it runs, or
 * waits for the pass in flight; and its removal is durable when that stage commits. A sleeping
 * instance wakes at the earliest pending processing-time timer. A {@link LoopMode#ONCE} instance
 * ends with its one pass, so a timer due after it never fires.
 *
 * <p>{@link RollbackMode} then selects what an exhausted retry ladder does: unwind the committed
 * stages through their tasklets' {@code compensate} methods, or retain progress. A tasklet's undo
 * handle belongs in its state rather than its emitted output, because state is restored on resume
 * and output is not.
 */
public interface WorkflowOperative extends Operative {

    /**
     * Optional wall-clock backstop. {@link Optional#empty()} (the default) means
     * no duration bound; when present, the SDK cooperatively cancels in-flight
     * work once it elapses.
     */
    @Nonnull
    default Optional<Duration> maxDuration() {
        return Optional.empty();
    }

    /**
     * Self-termination guarantee, validated at registration. Default {@code true}:
     * the operative ends on its own (a chain that runs to its end, a tasklet
     * calling {@code ctx.complete}, or a loop with exhausted input). An operative whose
     * <strong>only</strong> possible exit is an external stop returns
     * {@code false}; combined with no {@link #maxDuration} it is
     * <strong>rejected</strong> at registration with a message directing the
     * author to add a terminal condition.
     */
    default boolean selfTerminating() {
        return true;
    }

    /**
     * How many times this Operative runs its chain. Defaults to
     * {@link LoopMode#ONCE}: one pass.
     */
    @Nonnull
    default LoopMode loopMode() {
        return LoopMode.ONCE;
    }

    /**
     * Saga rollback scope, applied when the operative terminates abnormally
     * after partial progress. Default {@link RollbackMode#ALL_OR_NOTHING} undoes
     * every completed tasklet in reverse, back to the start;
     * {@link RollbackMode#KEEP_PROGRESS} keeps each finished loop iteration and
     * undoes only the in-flight one. The mode is irrelevant for a single-pass
     * chain (where both behave all-or-nothing) and for an operative with no
     * compensation hooks.
     */
    default RollbackMode rollbackMode() {
        return RollbackMode.ALL_OR_NOTHING;
    }

    /**
     * Class-level persistence toggle (durable by default, mirroring
     * {@code StreamOperative.persistenceEnabled()}). When {@code true}, a
     * structured workflow operative's per-Cell Changes are written to the WAL
     * as they apply, so a crash resumes from persisted state. When
     * {@code false}, the per-Cell flush drains and discards Changes with no
     * serialization or wire I/O — the operative is in-memory only (a crash
     * re-runs from the top). The runtime announces it to the controller,
     * which resolves it with a per-class operator override.
     */
    default boolean persistenceEnabled() {
        return true;
    }

    /**
     * Whether an idle instance sleeps: one waiting at a committed boundary
     * for {@code FLEZHA_PARK_IDLE_MS} is evicted from its runner and wakes on
     * its next message or its wake deadline. Defaults to
     * {@link #persistenceEnabled()}; declaring it with persistence off is
     * refused at registration as {@code SleepWithoutPersistence}.
     */
    default boolean sleepWhenIdle() {
        return persistenceEnabled();
    }

    /**
     * Synchronous remote durability: the controller completes the remote ship
     * of each commit barrier — the task-journal suffix and the inbox the
     * barrier consumed — before it acknowledges the barrier. The
     * acknowledgement this driver blocks on therefore implies the commit
     * survives losing the controller's disk (RPO 0), at the cost of one remote
     * round trip per barrier.
     *
     * <p>Inert unless the controller has a durability backend configured, and
     * available to workflow classes only: a stream class acknowledges at input
     * rates, where a remote round trip per acknowledgement is not a guarantee
     * anyone can keep.
     */
    default boolean syncDurability() {
        return false;
    }
}
