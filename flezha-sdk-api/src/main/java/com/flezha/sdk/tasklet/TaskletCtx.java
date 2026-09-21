package com.flezha.sdk.tasklet;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * The runtime handle passed to every {@link Tasklet#run} call: emit output, read the clock, ask
 * for a replay-stable identifier, register a timer, request a worker.
 *
 * <pre>{@code
 * @Tasklet(id = "add_to_cart", emits = String.class)
 * public final class AddToCart {
 *     public void run(Order order, CartHandle cart, TaskletCtx ctx) throws TaskletException {
 *         cart.pushItems(order.item());
 *         cart.setUpdatedMs(ctx.now());          // ctx.now(), never System.currentTimeMillis()
 *         ctx.emit("added " + order.item());     // where this goes is the operative's business
 *     }
 * }
 * }</pre>
 *
 * <p>The three members worth knowing before anything else:
 *
 * <ul>
 *   <li>{@link #emit} sends a value onward. The destination is declared by the operative, not here,
 *       so a graph can be rewired without touching the tasklet.
 *   <li>{@link #now()} is the invocation's time, computed once by the runtime from the tasklet's
 *       declared clock and served identically on a replay. Reading the system clock instead makes
 *       the reconstructed state diverge from what was observed.
 *   <li>{@link #stableId()} is an identifier derived deterministically, so a re-executed invocation
 *       produces the same one. An external system then sees a retry rather than a second request.
 * </ul>
 *
 * <p>The context belongs to one invocation and borrows the runner's per-cell routing tables. It
 * must not be retained past the call or handed to another thread.
 */
public interface TaskletCtx {

    /**
     * Reserved metadata namespace: the runtime's identity stamps
     * ({@code flz.cell}, {@code flz.seq}, {@code flz.key}) live here.
     * User-supplied keys with this prefix are rejected at emit time so a stamp
     * can never be spoofed or clobbered.
     */
    String RESERVED_METADATA_PREFIX = "flz.";

    /**
     * The invocation's time value, computed <b>once</b> by the runtime from the
     * Tasklet's declared {@code Clock} before {@code run} executes: epoch
     * milliseconds for {@code WallClock}, the extracted input timestamp for
     * {@code EventTimeFrom}, the durable change seq for {@code LogicalClock}.
     * Every call within one invocation returns the same value.
     *
     * <p>This is the determinism-contract replacement for
     * {@code System.currentTimeMillis()}: a run that reads ambient time cannot
     * be replayed, because the replay would compute different outputs from the
     * same inputs.
     */
    long now();

    /**
     * A deterministic, replay-stable 128-bit identifier. Repeated calls within
     * one invocation differ; the n-th call of a replayed invocation equals the
     * n-th call of the original.
     *
     * <p>This is the determinism-contract replacement for
     * {@code UUID.randomUUID()}.
     */
    @Nonnull
    StableId stableId();

    /**
     * Close the current checkpoint interval: acknowledge every input consumed
     * since the last checkpoint, and make the state accumulated across them
     * durable — as one record.
     *
     * <p>Call it where the accumulated state is genuinely coherent (a closed
     * window, a completed batch). The interval you leave open is the work a
     * crash replays and the work the controller's input log retains.
     *
     * <p>A no-op unless the Operative declared {@code checkpointAck()}.
     */
    void checkpoint();

    /**
     * End the workflow this tasklet runs in, with {@code result}. The call does not stop the
     * iteration: its remaining tasklets still run and commit, and the instance then ends with
     * status {@code ok} and this result instead of waiting for its next input, re-seeding a chained
     * loop or parking. In a {@code ONCE} workflow it is the result the single pass ends with.
     *
     * <p>A later call in the same invocation replaces an earlier one. When several cells call it
     * in one iteration, the instance ends once, with the result of the tasklet latest in the
     * chain and, within it, of the greatest cell key.
     *
     * <p>A stream never completes: a stream tasklet calling this fails its input with a
     * non-retryable {@code CompleteOutsideWorkflow} error.
     */
    void complete(byte[] result);

    /** {@link #complete(byte[])} with the UTF-8 bytes of {@code result}. */
    default void complete(String result) {
        complete(result.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Ask the controller for a new workflow instance of {@code factoryId}, under an id it
     * generates, with {@code payload} as the instance's first input.
     *
     * <p>Returns a handle at once. The request cannot fault this tasklet: a refusal, an
     * unreachable controller or an unknown factory is reported only by a read of the returned
     * {@link SpawnHandle}, and a failure no read collected is logged once when this invocation
     * ends. Read the handle inside this invocation.
     *
     * <p>Without an id every call starts a new instance and never delivers into a running one. A
     * spawn reissued after a lost or timed-out answer starts a second instance, and a replayed
     * invocation answers an empty id, because the generated one existed only in the live run's
     * answer. A caller who needs at most one instance, or the id on replay, passes its own id
     * through {@link #spawnWorkflow(String, byte[], SpawnOptions)}.
     *
     * <p>Spawning is a side effect of <b>live</b> processing. Replayed input (a crash-resume or a
     * late-input correction re-running this invocation) issues no request and answers
     * {@link SpawnOutcome#SKIPPED_ON_REPLAY}, so a replay never doubles the workers a run created.
     *
     * <p>The timer calls on this same context behave the OPPOSITE way and deliberately so: a timer
     * is internal state, so a replay registers it, where skipping would rebuild a pending set the
     * live run never had.
     */
    @Nonnull
    default SpawnHandle spawnWorkflow(String factoryId, byte[] payload) {
        return SpawnRequests.request(activationToken(), factoryId, "", payload, IfRunning.DELIVER, replaying());
    }

    /**
     * Ask the controller for the workflow instance {@code options} names: start {@code factoryId}
     * under that id if it is free, otherwise apply its {@link IfRunning} policy
     * ({@link IfRunning#DELIVER}, the default, feeds the running instance; {@link IfRunning#ERROR}
     * reports {@link SpawnException.AlreadyRunning}). {@code payload} is the instance's first input
     * on a spawn, one more input on a delivery.
     *
     * <p>Concurrent requests for one id resolve controller-side: exactly one spawns and every other
     * payload is delivered to it. A replayed invocation answers
     * {@link SpawnOutcome#SKIPPED_ON_REPLAY} with that id. The handle, the failures and the replay
     * rule are those of {@link #spawnWorkflow(String, byte[])}.
     */
    @Nonnull
    default SpawnHandle spawnWorkflow(String factoryId, byte[] payload, SpawnOptions options) {
        return SpawnRequests.request(activationToken(), factoryId, options.id, payload, options.ifRunning, replaying());
    }

    /**
     * Whether this invocation is re-running already-processed input. Runtime
     * detail: the dispatcher overrides it, and it is what makes
     * {@link #spawnWorkflow} a live-processing-only side effect.
     */
    default boolean replaying() {
        return false;
    }

    /**
     * The poll core's token for the activation this invocation runs under, which every spawn
     * handle it makes belongs to. Runtime detail: the dispatcher overrides it; 0 names none.
     */
    default long activationToken() {
        return 0;
    }

    /**
     * The key this Cell was routed by — the one {@code initState} was handed
     * — or empty for a single-cell Tasklet. It is part of the invocation's
     * identity, so reading it is replay-stable; a keyed-channel emit that
     * supplies no key of its own is emitted under it.
     */
    @Nonnull
    default Optional<String> cellKey() {
        return Optional.empty();
    }

    /**
     * Register an <b>event-time</b> timer at an absolute timestamp, returning
     * that timestamp. It fires when this Cell's event-time high-water reaches
     * it — so a Cell that stops receiving input never fires one, however much
     * wall-clock time passes. That is inherent to event time;
     * {@link #timerAfter} is the answer to "nothing has arrived".
     *
     * <p>Refused with
     * {@link TaskletException.EventTimeTimerWithoutEventTimeClock} unless the
     * Tasklet declares {@code EventTimeFrom} as its Clock.
     *
     * <p>Registering the same timestamp twice is idempotent — pending timers are
     * a set. Handle the firing in {@link Tasklet#onTimer}.
     */
    default long timerAtEventTime(long scheduledAt) throws TaskletException {
        throw new TaskletException.TimersUnavailable();
    }

    /**
     * Register a <b>processing-time</b> timer {@code delay} after {@link #now},
     * returning the absolute timestamp it is scheduled at.
     *
     * <p>The anchor is {@code ctx.now()} — the declared Clock — not an ambient
     * read, which is what makes a replayed invocation register the same absolute
     * timestamp the live one did. Under an {@code EventTimeFrom} clock the
     * anchor is therefore the input's event time: a stream that is caught up
     * schedules the same instant a wall clock would, and one replaying history
     * schedules a past instant, which fires at once.
     */
    default long timerAfter(Duration delay) throws TaskletException {
        throw new TaskletException.TimersUnavailable();
    }

    /**
     * Cancel a pending event-time timer by the timestamp
     * {@link #timerAtEventTime} returned. {@code false} if none was pending.
     */
    default boolean cancelEventTimeTimer(long scheduledAt) throws TaskletException {
        throw new TaskletException.TimersUnavailable();
    }

    /**
     * Cancel a pending processing-time timer by the timestamp
     * {@link #timerAfter} returned. {@code false} if none was pending.
     */
    default boolean cancelProcessingTimeTimer(long scheduledAt) throws TaskletException {
        throw new TaskletException.TimersUnavailable();
    }

    /**
     * Emit on a channel declared {@code .keyed()}, supplying the output's
     * natural key — for the output whose key differs from the key its Cell
     * was routed by, since {@link #emit} already carries that one. The runtime
     * stamps {@code flz.key} (alongside {@code flz.cell} and
     * {@code flz.seq}) on the plugin frame, which is what lets a keyed sink
     * absorb a replayed emit as an idempotent upsert rather than a duplicate
     * row.
     *
     * <p>The key must itself be replay-stable by the determinism contract —
     * derived from the input, the state, or {@code ctx}, never from an ambient
     * source. Emitting a key on a channel not declared {@code .keyed()} throws
     * {@link TaskletException.KeyNotAllowedOnNonKeyedChannel}.
     */
    <C> void emitKeyed(String key, C value) throws TaskletException;

    /** {@link #emitKeyed} with user-attached metadata. */
    <C> void emitKeyedWithMeta(String key, C value, Map<String, String> meta) throws TaskletException;

    /**
     * Emit a typed value on this Tasklet's declared output channel. The channel
     * id was resolved once, when the operative's wiring was registered, so this
     * call performs no name lookups.
     *
     * <p>Inter-tasklet channels: the value is passed by reference to consumer
     * Tasklets; the user MUST treat it as immutable from the producer side
     * after this call returns (see {@code design.md} Decision 4).
     *
     * <p>Plugin-bound channels: the value is serialized synchronously via the
     * channel's serializer and queued for the wire with
     * {@code metadata = {"channel": "<name>"}}.
     *
     * <p>Channels declared {@code .keyed()}: the emit carries this Cell's own
     * key ({@link #cellKey}) — equal to the routing key by construction, so
     * it cannot drift from it. A single-cell Tasklet has no such key and
     * throws {@link TaskletException.KeyRequiredOnKeyedChannel}; an output
     * whose natural key differs from the Cell's uses {@link #emitKeyed}.
     */
    <C> void emit(C value) throws TaskletException;

    /**
     * Emits on a named channel, for a tasklet that produces on more than one.
     *
     * @param channel the channel name to release on, as given to {@code named(...)} in the wiring
     * @param value the value to emit
     * @throws TaskletException if the channel is unknown, or the channel is keyed and this cell
     *     has no key to stamp
     */
    <C> void emit(String channel, C value) throws TaskletException;

    /**
     * Emit with user-attached string-keyed metadata. Valid only on plugin-bound
     * channels; inter-tasklet emits throw
     * {@link TaskletException.MetadataNotAllowedOnInterTaskletChannel} naming the
     * channel.
     *
     * <p>The reserved {@code channel} key is dropped from the user map and
     * overwritten with the channel's actual name during framing. Keys in the
     * {@link #RESERVED_METADATA_PREFIX} namespace are rejected with
     * {@link TaskletException.ReservedMetadataKey}.
     */
    <C> void emitWithMeta(C value, Map<String, String> meta) throws TaskletException;

    /** Same as {@link #emitWithMeta(Object, Map)} but pinned to a specific channel name. */
    <C> void emitWithMeta(String channel, C value, Map<String, String> meta) throws TaskletException;
}
