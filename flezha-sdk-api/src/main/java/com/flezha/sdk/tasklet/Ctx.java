package com.flezha.sdk.tasklet;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.PlainPayload;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * One invocation's context, passed to {@link Tasklet#run}, {@link Tasklet#onTimer} and
 * {@link Tasklet#compensate}: the cell's state and its changes, emits, the clock, a replay-stable
 * identifier, timers, steps spawns and kept outside calls.
 *
 * <pre>{@code
 * @Override
 * public void run(Order order, Ctx<Cart, String> ctx) throws TaskletException {
 *     ctx.change(new ItemAdded(order.item(), ctx.now()));   // ctx.now(), never System.currentTimeMillis()
 *     ctx.emit("added " + order.item());                    // where this goes is the relay's business
 * }
 * }</pre>
 *
 * <p>The members worth knowing before anything else:
 *
 * <ul>
 *   <li>{@link #state()} reads the cell's state and {@link #change} is the one recorded way to
 *       mutate it: the change is applied and written to the cell's log, from which the state is
 *       rebuilt after a restart.
 *   <li>{@link #emit} sends a value onward. The destination is declared by the relay, not here,
 *       so a graph can be rewired without touching the tasklet.
 *   <li>{@link #now()} is the invocation's time, computed once by the runtime from the tasklet's
 *       clock and served identically on a replay.
 *   <li>{@link #stableId()} is an identifier derived deterministically, so a re-executed invocation
 *       produces the same one. An external system then sees a retry rather than a second request.
 * </ul>
 *
 * <p>The context belongs to one invocation. It must not be retained past the call or handed to
 * another thread.
 *
 * @param <State> the tasklet's state type
 * @param <Output> the tasklet's output type, which {@link #emit(Object)} takes
 */
public interface Ctx<State, Output> {

    /**
     * The cell's state, including every change applied so far in this invocation.
     *
     * <p>The object returned is the live state, so assigning one of its fields compiles and runs.
     * Do not: such a write is not recorded and does not survive a restart. Mutate through
     * {@link #change} instead; {@code TaskletTestHarness.replayEquals()} catches a direct write.
     */
    State state();

    /**
     * Applies {@code change} to the state through its {@link com.flezha.sdk.state.Change#applyTo}
     * and records it in the cell's change log. This is the only mutation that survives a restart,
     * and a read through {@link #state()} right after it reflects it.
     */
    void change(com.flezha.sdk.state.Change<State> change);

    /**
     * Reserved metadata namespace: the runtime's identity stamps
     * ({@code flz.cell}, {@code flz.seq}, {@code flz.key}) live here.
     * User-supplied keys with this prefix are rejected at emit time so a stamp
     * can never be spoofed or clobbered.
     */
    String RESERVED_METADATA_PREFIX = "flz.";

    /**
     * The invocation's time value, computed <b>once</b> by the runtime from the
     * tasklet's clock before {@code run} executes: epoch milliseconds by
     * default, what {@code eventTime(input)} returns when the tasklet writes
     * it, the durable change seq under {@code @Tasklet.Spec(logicalClock = true)}.
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
     * <p>A no-op unless the relay acknowledges at a checkpoint
     * ({@link com.flezha.sdk.relay.Relay.Ack.Checkpoint}).
     */
    void checkpoint();

    /**
     * End the relay this tasklet runs in, with {@code result}. The call does not stop the
     * invocation's remaining work. In a relay that runs in steps, the iteration's remaining
     * tasklets still run and commit, and the instance then ends with status {@code ok} and this
     * result instead of waiting for its next input, re-seeding a chained loop or parking; in a
     * {@code ONCE} run it is the result the single pass ends with. In a flow relay the instance
     * runs the work its cells already hold to its outputs, then ends {@code completed}.
     *
     * <p>A later call in the same invocation replaces an earlier one. When several cells call it
     * in one iteration, the instance ends once, with the result of the tasklet latest in the
     * chain and, within it, of the greatest cell key.
     */
    void complete(byte[] result);

    /**
     * {@link #complete(byte[])} with {@code result} encoded as an output with no encoder encodes a
     * value ({@link PlainPayload}): a {@code String} as UTF-8, anything else as JSON.
     */
    default void complete(Object result) {
        complete(PlainPayload.encode(result));
    }

    /**
     * Ask the controller for a new steps instance of {@code factoryId}, under an id it
     * generates, with {@code payload} as the instance's first input. The payload is encoded as an
     * output with no encoder encodes a value ({@link PlainPayload}): a {@code byte[]} as it is, a
     * {@code String} as UTF-8, anything else as JSON, which the instance's entry channel decodes.
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
     * through {@link #spawnSteps(String, Object, SpawnOptions)}.
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
    default SpawnHandle spawnSteps(String factoryId, Object payload) {
        return SpawnRequests.request(
                activationToken(), factoryId, "", PlainPayload.encode(payload), null, replaying(), inputOffset());
    }

    /**
     * Ask the controller for the steps instance {@code options} names: start {@code factoryId}
     * under that id if it is free, otherwise apply its {@link IfRunning} policy
     * ({@link IfRunning#DELIVER}, the default, feeds the running instance; {@link IfRunning#ERROR}
     * reports {@code relay.already_running}). {@code payload} is the instance's first input
     * on a spawn, one more input on a delivery.
     *
     * <p>Concurrent requests for one id resolve controller-side: exactly one spawns and every other
     * payload is delivered to it. A replayed invocation answers
     * {@link SpawnOutcome#SKIPPED_ON_REPLAY} with that id. The handle, the failures and the replay
     * rule are those of {@link #spawnSteps(String, Object)}.
     */
    @Nonnull
    default SpawnHandle spawnSteps(String factoryId, Object payload, SpawnOptions options) {
        return SpawnRequests.request(
                activationToken(),
                factoryId,
                options.id,
                PlainPayload.encode(payload),
                options.ifRunning,
                replaying(),
                inputOffset());
    }

    /**
     * Whether this invocation is re-running already-processed input. Runtime
     * detail: the dispatcher overrides it, and it is what makes
     * {@link #spawnSteps} a live-processing-only side effect.
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
     * The input-log offset of the item this invocation runs, which a spawn request carries so a
     * crash re-run of the same input resolves to the worker the first run spawned. Runtime detail:
     * the dispatcher overrides it; -1 names none (a timer firing, a class without durable input).
     */
    default long inputOffset() {
        return -1;
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
     * <p>Unless the tasklet writes {@link Tasklet#eventTime}, the item fails when it settles, not
     * retried, with {@code runtime.event_time_timer_without_event_time_clock}.
     *
     * <p>Registering the same timestamp twice is idempotent — pending timers are
     * a set. Handle the firing in {@link Tasklet#onTimer}.
     */
    default long timerAtEventTime(long scheduledAt) {
        throw FlezhaException.of("runtime.timers_unavailable", Map.of());
    }

    /**
     * Register a <b>processing-time</b> timer {@code delay} after {@link #now},
     * returning the absolute timestamp it is scheduled at.
     *
     * <p>The anchor is {@code ctx.now()}, the tasklet's clock, not an ambient
     * read, which is what makes a replayed invocation register the same absolute
     * timestamp the live one did. Under an event-time clock the
     * anchor is therefore the input's event time: a flow relay that is caught up
     * schedules the same instant a wall clock would, and one replaying history
     * schedules a past instant, which fires at once.
     */
    default long timerAfter(Duration delay) {
        throw FlezhaException.of("runtime.timers_unavailable", Map.of());
    }

    /**
     * Cancel a pending event-time timer by the timestamp
     * {@link #timerAtEventTime} returned; cancelling one that is not pending changes nothing.
     */
    default void cancelEventTimeTimer(long scheduledAt) {
        throw FlezhaException.of("runtime.timers_unavailable", Map.of());
    }

    /**
     * Cancel a pending processing-time timer by the timestamp
     * {@link #timerAfter} returned; cancelling one that is not pending changes nothing.
     */
    default void cancelProcessingTimeTimer(long scheduledAt) {
        throw FlezhaException.of("runtime.timers_unavailable", Map.of());
    }

    /**
     * Emit on a channel declared {@code .keyed()}, supplying the output's
     * natural key — for the output whose key differs from the key its Cell
     * was routed by, since {@link #emit} already carries that one. The runtime
     * stamps {@code flz.key} (alongside {@code flz.cell} and
     * {@code flz.seq}) on the plugin frame, which is what lets a keyed output
     * absorb a replayed emit as an idempotent upsert rather than a duplicate
     * row.
     *
     * <p>The key must itself be replay-stable by the determinism contract —
     * derived from the input, the state, or {@code ctx}, never from an ambient
     * source. Emitting a key on a channel not declared {@code .keyed()} throws
     * {@code runtime.key_not_allowed_on_non_keyed_channel}.
     */
    void emitKeyed(String key, Output value) throws TaskletException;

    /** {@link #emitKeyed} with user-attached metadata. */
    void emitKeyedWithMeta(String key, Output value, Map<String, String> meta) throws TaskletException;

    /**
     * Emit a typed value on this Tasklet's declared output channel. The channel
     * id was resolved once, when the relay's wiring was registered, so this
     * call performs no name lookups.
     *
     * <p>Inter-tasklet channels: the value is passed by reference to consumer
     * Tasklets; the user MUST treat it as immutable from the producer side
     * after this call returns.
     *
     * <p>Plugin-bound channels: the value is serialized synchronously via the
     * channel's serializer and queued for the wire with
     * {@code metadata = {"channel": "<name>"}}.
     *
     * <p>Channels declared {@code .keyed()}: the emit carries this Cell's own
     * key ({@link #cellKey}) — equal to the routing key by construction, so
     * it cannot drift from it. A single-cell Tasklet has no such key and
     * throws {@code runtime.key_required_on_keyed_channel}; an output
     * whose natural key differs from the Cell's uses {@link #emitKeyed}.
     */
    void emit(Output value) throws TaskletException;

    /**
     * Send a part: a preview of the output this invocation is producing, which leaves the runner
     * now rather than when the invocation returns. A listener attached to the relay receives
     * it; an output never does, and nothing about the part is durable or replayed.
     *
     * <p>The part previews this tasklet's own output channel, and {@code value} is encoded exactly
     * as {@link #emit} would encode it there. The call never waits: a part that cannot leave at
     * once is dropped and counted, and a replayed input sends none. The runtime numbers the parts
     * of each attempt from 0 and stamps the attempt, the Cell and the channel.
     *
     * @throws com.flezha.sdk.FlezhaException {@code part.no_output} if the output channel does not
     *     end in an output (a {@code call(...)} end is not one), {@code part.outside_invocation} if
     *     this context's invocation has already returned
     */
    void part(Output value);

    /**
     * Runs {@code fn}, a call to anything outside the instance (a model, a tool, a service), and
     * keeps its result: when this invocation runs again (a crash re-run, a replay, a wake, a move),
     * the call returns the kept result and {@code fn} does not run.
     *
     * <pre>{@code
     * String answer = ctx.effect("model", String.class, () -> model.ask(question));
     * }</pre>
     *
     * <p>The result is kept, synced on the controller, before this returns. A crash between
     * {@code fn} finishing and that point runs {@code fn} again, so give the outside service
     * {@link #stableId()} as an idempotency key where it takes one. {@code name} names the call: a
     * non-empty string of at most 255 bytes, counted per invocation, so a call of another name added
     * before this one leaves its kept result where it was. One call runs at a time; a concurrent
     * one is refused.
     *
     * <p>The result is encoded as an output with no encoder encodes a value ({@link PlainPayload}:
     * a {@code byte[]} as it is, a {@code String} as UTF-8, anything else as JSON) and read back as
     * {@code type}. The call returns it as it reads back, on a first run and a re-run alike.
     *
     * <p>An invocation that took no input-log offset (local mode, a relay with no input log) runs
     * {@code fn} each time and keeps nothing. An exception {@code fn} throws propagates unchanged and
     * nothing is kept; a checked one is wrapped by the author, as {@link Supplier} declares none.
     *
     * @throws TaskletException.NonRetryable for {@code effect.bad_name}, {@code effect.nested},
     *     {@code effect.outside_invocation} and {@code effect.too_large}, with the
     *     {@link FlezhaException} as its cause
     * @throws FlezhaException {@code effect.not_recorded} when the controller did not keep the
     *     result, which the invocation's retry runs again
     */
    <T> T effect(String name, Class<T> type, Supplier<T> fn) throws TaskletException;

    /**
     * Emits on a named channel, for a tasklet that produces on more than one.
     *
     * @param channel the channel name to release on, as given to {@code named(...)} in the wiring
     * @param value the value to emit
     * @throws com.flezha.sdk.FlezhaException {@code runtime.unknown_channel} if the channel is
     *     unknown, {@code runtime.key_required_on_keyed_channel} if it is keyed and this cell has no
     *     key to stamp
     */
    <C> void emit(String channel, C value) throws TaskletException;

    /**
     * Emit with user-attached string-keyed metadata. Valid only on plugin-bound
     * channels; inter-tasklet emits throw
     * {@code runtime.metadata_on_inter_tasklet_channel} naming the channel.
     *
     * <p>The reserved {@code channel} key is dropped from the user map and
     * overwritten with the channel's actual name during framing. Keys in the
     * {@link #RESERVED_METADATA_PREFIX} namespace are rejected with
     * {@code runtime.reserved_metadata_key}.
     */
    void emitWithMeta(Output value, Map<String, String> meta) throws TaskletException;

    /** Same as {@link #emitWithMeta(Object, Map)} but pinned to a specific channel name. */
    <C> void emitWithMeta(String channel, C value, Map<String, String> meta) throws TaskletException;
}
