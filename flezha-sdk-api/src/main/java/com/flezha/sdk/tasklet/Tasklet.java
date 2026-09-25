package com.flezha.sdk.tasklet;

import com.flezha.sdk.internal.RuntimeBridge;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.CoalesceKey;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.state.WallClock;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * The unit of user code: handles one message at a time for one cell.
 *
 * <p>A tasklet is where your logic goes. The runtime holds the per-cell state and threads it
 * into {@link #run} through a {@link StateHandle}, so the class itself carries no mutable fields of
 * its own that matter across messages.
 *
 * <p>Most tasklets are written as a class annotated
 * {@link com.flezha.sdk.authoring.Tasklet @Tasklet}, from whose {@code run} method the
 * annotation processor generates this interface's implementation, {@code <Class>Tasklet}.
 * Implement the interface directly for what the annotation does not express: a hand-written
 * {@link Change}, a clock other than {@link WallClock}, a configuration type, {@link #onTimer} or
 * {@link #compensate}.
 *
 * <p>A stateless tasklet declares {@link NoChange} and is a plain function of its input:
 *
 * <pre>{@code
 * public final class EchoTasklet
 *         implements Tasklet<String, EchoTasklet.None, String, NoChange, WallClock, Void> {
 *
 *     public record None() {}
 *
 *     @Override
 *     public String name() {
 *         return "echo";
 *     }
 *
 *     @Override
 *     public None initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
 *         return new None();
 *     }
 *
 *     @Override
 *     public void run(String input, StateHandle<None, NoChange> state, TaskletCtx ctx) {
 *         ctx.emit("echo: " + input);
 *     }
 * }
 * }</pre>
 *
 * <p>A tasklet that remembers something declares a change type instead of {@code NoChange}, and
 * mutates state only through {@link StateHandle#apply}, never by assigning a field:
 *
 * <pre>{@code
 * public record Increment(long atMs) implements Change {}
 *
 * @Override
 * public void applyChange(Counter state, Increment change) {
 *     state.count++;              // the ONLY place a field is written
 * }
 *
 * @Override
 * public void run(String input, StateHandle<Counter, Increment> state, TaskletCtx ctx) {
 *     state.apply(new Increment(ctx.now()));
 *     ctx.emit("count=" + state.get().count);
 * }
 * }</pre>
 *
 * <p>The reason for that indirection is that state is persisted as the ordered log of applied
 * changes and reconstructed by replaying it, so a directly assigned field is absent from the log
 * and absent from the reconstructed object. The failure is silent under normal operation.
 *
 * <h2>Ordering and concurrency</h2>
 *
 * <p>Invocation is serialised per cell: two messages carrying the same key are never handled
 * concurrently, so no locking is needed around per-cell state. Messages for distinct keys are
 * handled concurrently, on this host or another, and the runtime holds one tasklet instance per
 * cell rather than sharing one across them.
 *
 * <h2>Determinism</h2>
 *
 * <p>Recovery re-executes {@link #run}, so it must be a deterministic function of its declared
 * inputs. Reading {@code System.currentTimeMillis()} or generating a random identifier yields a
 * different result on replay and the reconstructed state then diverges from what was observed. Use
 * {@code ctx.now()} for time and {@link StableId} for identifiers.
 *
 * <h2>The six type arguments</h2>
 *
 * <p>The runtime needs runtime {@link Class} handles for all six, and recovers them by default from
 * the implementing class's generic supertype chain: a direct {@code implements Tasklet<...>} with
 * concrete arguments, or a concrete subclass of a generic intermediate class. An argument still
 * generic at the concrete class is never guessed. Override the single corresponding accessor in
 * that case ({@link #inputType()}, {@link #outputType()}, {@link #changeType()},
 * {@link #clockType()}, {@link #configType()}) or registration fails naming the class and the
 * parameter. An explicit override always wins.
 *
 * <p>The optional hooks, {@link #compensate}, {@link #cellsFor}, {@link #eventTime},
 * {@link #mailboxCapacity} and {@link #maxCells}, are declared by writing them: registration reads
 * which ones the class overrides, itself or through a class above it.
 *
 * <p>Implementations must provide a public no-argument constructor.
 *
 * @param <Input> the value handed to {@link #run}, decoded from the channel that feeds this
 *     tasklet
 * @param <State> the per-cell state, held by the runtime and Kryo-serialisable
 * @param <Output> the value emitted through {@link TaskletCtx}
 * @param <ChangeT> how a state mutation is recorded; {@link NoChange} opts out of persistence
 *     entirely
 * @param <ClockT> the clock {@code ctx.now()} reads; {@link WallClock} is processing time, and
 *     {@link com.flezha.sdk.state.EventTimeFrom} takes the time from the message itself
 * @param <ConfigT> the Jackson-deserialised configuration type; {@link Void} when the tasklet
 *     takes none
 */
public interface Tasklet<
        Input, State, Output, ChangeT extends Change, ClockT extends com.flezha.sdk.state.Clock, ConfigT> {

    /**
     * This tasklet's name, unique within its operator.
     *
     * <p>The name identifies the tasklet in durable state and in the channel labels derived from
     * it, so changing it means the runtime no longer recognises what it already recorded.
     *
     * @return the stable name, unique among the tasklets of one operator
     */
    @Nonnull
    String name();

    /**
     * The input type, recovered from the {@code implements} clause. Override only when the type
     * argument is still generic at your concrete class, which the runtime will not guess.
     *
     * @return the class of values this tasklet receives
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    default Class<Input> inputType() {
        return (Class<Input>) TypeTokens.require(getClass(), TypeTokens.Token.INPUT);
    }

    /**
     * The output type, recovered from the {@code implements} clause. Override only when the type
     * argument is still generic at your concrete class.
     *
     * @return the class of values this tasklet emits
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    default Class<Output> outputType() {
        return (Class<Output>) TypeTokens.require(getClass(), TypeTokens.Token.OUTPUT);
    }

    /**
     * The change type, recovered from the {@code implements} clause. {@link NoChange} here means
     * the tasklet persists nothing.
     *
     * @return the class of recorded state changes
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    default Class<ChangeT> changeType() {
        return (Class<ChangeT>) TypeTokens.require(getClass(), TypeTokens.Token.CHANGE);
    }

    /**
     * The clock type, recovered from the {@code implements} clause. Determines what
     * {@code ctx.now()} returns.
     *
     * @return the declared clock class
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    default Class<ClockT> clockType() {
        return (Class<ClockT>) TypeTokens.require(getClass(), TypeTokens.Token.CLOCK);
    }

    /**
     * The configuration type, recovered from the {@code implements} clause. {@code Void.class} when
     * the tasklet takes no configuration.
     *
     * @return the declared configuration class
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    default Class<ConfigT> configType() {
        return (Class<ConfigT>) TypeTokens.require(getClass(), TypeTokens.Token.CONFIG);
    }

    /**
     * Builds the starting state for one cell, called once on first sight of that cell.
     *
     * <p>On a resume the runtime restores the most recent snapshot and folds the recorded changes
     * over whatever this returns, so it only has to be correct for a cold start.
     *
     * @param cellKey the key this cell is for. Empty when the tasklet runs as a single
     *     cell, present when the stream is keyed or the tasklet declares static keys
     * @param initPayload state handed over when this cell is being relocated from another host
     *     rather than started cold. Empty on a cold start. The runtime applies the payload itself
     *     after this method returns, so an implementation normally ignores it; it is exposed for
     *     instrumentation, such as recording the transferred byte size
     * @return the initial state for this cell
     */
    @Nonnull
    State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload);

    /**
     * Applies one recorded change to the state. This is the only method permitted to write state
     * fields.
     *
     * <p>The runtime calls it when {@link StateHandle#apply} is invoked, and again for every
     * recorded change when rebuilding a cell after a restart. It must therefore apply the change
     * and do nothing else: no emitting, no clock reads, no external calls, since anything done here
     * happens again on every replay.
     *
     * <p>A tasklet declaring a change type must override this. The default throws, rather than
     * silently discarding state, so the omission is found at the first change instead of at the
     * first restart.
     *
     * @param state the cell's current state, mutated in place
     * @param change the change to apply, as passed to {@link StateHandle#apply}
     */
    default void applyChange(State state, ChangeT change) {
        if (changeType() == NoChange.class) {
            return;
        }
        throw new UnsupportedOperationException(
                "Tasklet '" + name() + "' declares Change=" + changeType().getSimpleName()
                        + " but does not override applyChange. Override Tasklet.applyChange to implement state mutation.");
    }

    /**
     * Handle a timer this Tasklet registered via
     * {@link TaskletCtx#timerAtEventTime} / {@link TaskletCtx#timerAfter}.
     * Receives the same {@link StateHandle} and {@link TaskletCtx} as
     * {@link #run}, so it may apply Changes, emit, and register further timers.
     *
     * <p>{@code firedAt} is the timestamp the timer was <b>scheduled at</b>,
     * never the wall clock at which it actually fired: a processing-time timer
     * set for T that fires at T+δ still reports T. Passing T+δ would make a
     * replay observe a different value and break the determinism contract in
     * exactly the way {@code System.currentTimeMillis()} inside {@code run}
     * does. For the same reason {@link TaskletCtx#now} equals {@code firedAt}
     * inside this call, so a chained {@code ctx.timerAfter(..)} schedules off
     * the timer that woke it.
     *
     * <p>A failure here climbs the input channel's retry ladder and parks to the
     * dead-letter queue exactly as a failing {@code run} does. The fired timer's
     * removal is durable either way, so a poison timer parks once instead of
     * firing forever.
     *
     * <p>The default throws naming the Tasklet: registering a timer without
     * handling it is an authoring error, and registration is a runtime call, so
     * no build-time check can catch it.
     */
    default void onTimer(
            long firedAt, com.flezha.sdk.state.TimerKind kind, StateHandle<State, ChangeT> state, TaskletCtx ctx)
            throws TaskletException {
        throw new UnsupportedOperationException("Tasklet '" + name()
                + "' registered a timer but does not override onTimer. Override"
                + " Tasklet.onTimer(firedAt, kind, state, ctx) to handle the firing.");
    }

    /**
     * Per-Cell bound on pending timers. Registering past it throws
     * {@link TaskletException.TimerCapExceeded} naming this Tasklet and the
     * bound, rather than allocating — the same posture as the soft cap the
     * dynamic-cell registry puts on Cells per Tasklet.
     */
    default int maxTimersPerCell() {
        return 10_000;
    }

    /** Optional coalesce key. Default: dispatches to the Change's own key. */
    @Nonnull
    default Optional<CoalesceKey> coalesceKey(ChangeT change) {
        return change.coalesceKey();
    }

    /**
     * Per-Cell WAL size threshold before the controller solicits a snapshot.
     * Default 8 MiB, in lockstep with the Rust SDK's {@code compact_after_bytes}
     * and the controller's {@code :default_compact_after_bytes}.
     */
    default long compactAfterBytes() {
        return 8L * 1024 * 1024;
    }

    /**
     * Per-Tasklet schema version stamped into every persisted record this
     * Tasklet writes — change records and state snapshots alike. Default: 1.
     *
     * <p>Bump it on any non-additive edit to the State <b>or</b> the Change
     * type. Kryo encodes them positionally, so reordering fields, removing one,
     * or changing a field's type is not a compatibility question the codec can
     * answer. The version turns that into a decision instead of a silent
     * corruption.
     *
     * <p>A bump alone refuses the old records. Declare
     * {@link #minReadableSchemaVersion()} and the migration hooks to read them.
     */
    default int schemaVersion() {
        return 1;
    }

    /**
     * The oldest {@link #schemaVersion()} whose persisted records this
     * Tasklet can still read. Defaults to its own version — "reads only what
     * it wrote".
     *
     * <p>Lowering it is a promise that {@link #migrateState} and
     * {@link #migrateChange} handle every version in between. A record below
     * the floor, and any record stamped above {@link #schemaVersion()} (older
     * code meeting newer state), is refused rather than guessed at.
     */
    default int minReadableSchemaVersion() {
        return schemaVersion();
    }

    /**
     * Rebuild the State from a snapshot written at an older schema version.
     * Invoked only for records between {@link #minReadableSchemaVersion()} and
     * {@link #schemaVersion()}; {@code bytes} is the snapshot body exactly as
     * that version wrote it.
     *
     * <p>Keep the old type in your own source and decode it here — the runtime
     * knows only the types this build declares. Must be deterministic:
     * crash-resume, retry-replay and rewind all re-enter the restore fold.
     */
    @Nonnull
    default State migrateState(int fromVersion, byte[] bytes) {
        throw new IllegalStateException(
                "tasklet `" + name() + "` declares no state migration from schema_version " + fromVersion);
    }

    /**
     * Rebuild a Change from a record written at an older schema version. Same
     * contract as {@link #migrateState}; a WAL legitimately holds records from
     * more than one version, so this is invoked per record.
     */
    @Nonnull
    default ChangeT migrateChange(int fromVersion, byte[] bytes) {
        throw new IllegalStateException(
                "tasklet `" + name() + "` declares no change migration from schema_version " + fromVersion);
    }

    /**
     * Stable Kryo type IDs for user types. Ordering of returned classes
     * determines their stable IDs (id N = list[N]). Append-only: adding a new
     * variant appends a new class to the END of the list, preserving prior IDs
     * across SDK versions.
     *
     * <p>Defaults to the resolved Change and State classes — the two types the
     * per-Cell codec always needs. A {@link NoChange} Tasklet persists
     * nothing and defaults to the empty list, as does one whose Change or State
     * argument is unresolvable.
     */
    @Nonnull
    default List<Class<?>> kryoTypes() {
        Class<?> change = TypeTokens.find(getClass(), TypeTokens.Token.CHANGE);
        Class<?> state = TypeTokens.find(getClass(), TypeTokens.Token.STATE);
        if (change == null || change == NoChange.class || state == null) {
            return List.of();
        }
        return List.of(change, state);
    }

    /**
     * Construct an instance from typed Config. Default for {@code Config = Void}
     * invokes the no-arg constructor via reflection. Tasklets with non-Void
     * Config types must override.
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    default Tasklet<Input, State, Output, ChangeT, ClockT, ConfigT> fromConfig(ConfigT cfg) {
        if (cfg == null && configType() == Void.class) {
            try {
                return (Tasklet<Input, State, Output, ChangeT, ClockT, ConfigT>) RuntimeBridge.construct(getClass());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Tasklet '" + name() + "' has no public no-arg constructor", e);
            }
        }
        throw new UnsupportedOperationException(
                "Tasklet '" + name() + "' has Config=" + configType().getSimpleName()
                        + " but does not override fromConfig. Override Tasklet.fromConfig to implement typed config.");
    }

    /**
     * Static cell declaration. Default returns an empty list which the
     * runtime interprets as "single-cell mode".
     */
    @Nonnull
    default List<String> cellKeys() {
        return List.of();
    }

    /**
     * For static mode: which cells does this input belong to? Returning
     * multiple keys causes the SDK to enqueue the input on each named
     * cell's mailbox (fan-out). Returning an empty list drops the input.
     * A tasklet declaring {@link #cellKeys()} must override this, or
     * registration refuses it.
     */
    @Nonnull
    default List<String> cellsFor(Input input) {
        return List.of();
    }

    /**
     * Per-Cell mailbox capacity. The consumer Cell allocates one
     * bounded ring buffer of this size at operator startup. Static-mode Tasklets
     * allocate one mailbox per declared cell key, all sharing the same
     * capacity.
     *
     * <p>Override precedence at registration time:
     * <ol>
     *   <li>This method's value, if the tasklet overrides it.</li>
     *   <li>{@code FLEZHA_MAILBOX_CAPACITY} env var (parsed as a positive
     *       integer).</li>
     *   <li>The built-in default ({@code 1024}).</li>
     * </ol>
     *
     * <p>Capacity must be in {@code 1..=1_048_576}; out-of-range values fail
     * {@code FlezhaRunner.registerFactory(...)} with the offending Tasklet's
     * name.
     */
    default int mailboxCapacity() {
        return 1024;
    }

    /**
     * Handles one message. This is where your logic goes.
     *
     * <p>Called once per message, for one cell, and never concurrently for the same cell. It is
     * also re-executed during recovery, so it must produce the same result given the same input:
     * read time through {@code ctx.now()} and generate identifiers through
     * {@code ctx.stableId()} rather than reaching for the system clock or a random source.
     *
     * <pre>{@code
     * public void run(String input, StateHandle<Counter, Increment> state, TaskletCtx ctx) {
     *     state.apply(new Increment(ctx.now()));
     *     ctx.emit("count=" + state.get().count);
     * }
     * }</pre>
     *
     * @param input the message, already decoded from the channel that feeds this tasklet
     * @param state this cell's state. Read it with {@code state.get()}, and mutate it only with
     *     {@code state.apply(change)}; a field assigned directly is not recorded and does not
     *     survive a restart
     * @param ctx the runtime handle for emitting output, reading the clock, obtaining a
     *     replay-stable identifier, and registering timers
     * @throws TaskletException to fail this invocation. The failure is recorded against the
     *     operator, tasklet and cell, and any output emitted earlier in this invocation is
     *     discarded rather than released. Any other runtime exception is treated the same way
     */
    void run(Input input, StateHandle<State, ChangeT> state, TaskletCtx ctx) throws TaskletException;

    /**
     * Optional saga <strong>compensation</strong> hook (bounded operators only).
     * A tasklet whose {@link #run} commits a reversible side effect overrides
     * this to undo it. The bounded driver fires it ONLY during abnormal-termination
     * rollback — never on a clean completion, never for an unbounded operator —
     * once per cell that ran, in reverse completion order, handing back that
     * cell's own collected outputs and its {@link StateHandle} so it can
     * reverse precisely. Compensation MUST be idempotent by author contract: a
     * crash mid-rollback may re-invoke it.
     *
     * <p>Overriding this is what registers the tasklet as compensable; one
     * that does not is skipped during rollback.
     */
    default void compensate(List<Output> completedOutput, StateHandle<State, ChangeT> state, TaskletCtx ctx)
            throws TaskletException {
        // No-op: a tasklet with no reversible effect needs no compensation.
    }

    /**
     * Extract the event time (epoch millis) carried by an input. Required when
     * the Tasklet declares {@code Clock = EventTimeFrom}: that clock has no
     * value to serve without an extractor, and {@code ctx.now()} returns what
     * this method reports.
     *
     * <p>Rust binds the extractor at compile time via
     * {@code EventTimeFrom<F: TimeExtractor<Input>>}. Java's erasure precludes
     * that, so declaring {@code EventTimeFrom} without overriding this fails
     * Operator registration — the same defect, caught before any input is
     * served.
     */
    default long eventTime(Input input) {
        throw new UnsupportedOperationException(
                "tasklet `" + name() + "` declares EventTimeFrom but does not override eventTime(Input)");
    }

    /**
     * How long a dynamic Cell may sit idle before it is evicted. Empty (the
     * default) means eviction is OFF: a dynamic Tasklet keeps every Cell it
     * ever created.
     *
     * <p>Eviction is <b>destructive</b>: it ships {@code delete_cell_state} and
     * a later input for the same key cold-starts a fresh Cell rather than
     * restoring the old one. Declare a TTL only where re-accumulating from scratch
     * is acceptable — a key that reappears does not get its history back.
     */
    @Nonnull
    default java.util.Optional<java.time.Duration> cellIdleTtl() {
        return java.util.Optional.empty();
    }

    /**
     * Dynamic-mode soft cap on live Cells for this Tasklet. At the cap,
     * inputs for a new key are dropped; existing keys are unaffected. This
     * bounds memory against a runaway {@code keyBy}, not admission — there is no
     * error path back to the controller.
     */
    default long maxCells() {
        return 100_000L;
    }
}
