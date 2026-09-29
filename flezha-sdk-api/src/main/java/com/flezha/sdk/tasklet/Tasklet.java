package com.flezha.sdk.tasklet;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.TimerKind;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * The unit of user code: handles one message at a time for one cell.
 *
 * <p>A tasklet is your own class. It implements this interface with its three types, carries
 * {@link Spec @Tasklet.Spec} for its name and settings, and is the class the operator names:
 *
 * <pre>{@code
 * @Tasklet.Spec(name = "track_prices")
 * public final class TrackPrices implements Tasklet<Trade, TrackPrices.PriceWindow, Move> {
 *
 *     public static final class PriceWindow {
 *         public double sum;
 *         public int count;
 *     }
 *
 *     public record PriceSeen(double price) implements Change<PriceWindow> {
 *         public void applyTo(PriceWindow window) {
 *             window.sum += price;
 *             window.count++;
 *         }
 *     }
 *
 *     @Override
 *     public void run(Trade trade, Ctx<PriceWindow, Move> ctx) {
 *         ctx.change(new PriceSeen(trade.price()));
 *         ctx.emit(new Move(trade.pair(), ctx.state().sum / ctx.state().count));
 *     }
 * }
 * }</pre>
 *
 * <p>The wiring names that class, {@code .into(TrackPrices.class)}. Nothing is generated.
 *
 * <p>State is persisted as the ordered log of changes and rebuilt by replaying it, so a state field
 * is written only by a {@link Change#applyTo}, reached through {@link Ctx#change}. A field
 * assigned directly is absent from the log and absent from the rebuilt state. {@link Void} as the
 * state keeps no state; {@link Void} as the output declares no output channel.
 *
 * <p>A new cell's state is the state class's no-arg constructor unless the class writes
 * {@link #initState}. The optional hooks, {@link #onTimer}, {@link #eventTime},
 * {@link #compensate}, {@link #cellsFor}, {@link #initState}, {@link #migrateState},
 * {@link #migrateChange} and {@link #kryoTypes}, are declared by writing them: registration reads
 * which ones the class overrides, itself or through a class above it.
 *
 * <h2>Ordering and concurrency</h2>
 *
 * <p>Invocation is serialised per cell: two messages carrying the same key are never handled
 * concurrently, so no locking is needed around per-cell state. The runtime holds one tasklet
 * instance per cell rather than sharing one across them.
 *
 * <h2>Determinism</h2>
 *
 * <p>Recovery re-executes {@link #run}, so it must be a deterministic function of its input and
 * state. Use {@code ctx.now()} for time and {@code ctx.stableId()} for identifiers, never the
 * system clock or a random source.
 *
 * <p>The three type arguments are read at registration from the class's {@code implements} clause
 * (or a concrete subclass of a generic intermediate class). One still generic at the concrete
 * class fails registration naming the class and the parameter. Implementations need a public
 * no-argument constructor.
 *
 * @param <Input> the value handed to {@link #run}, decoded from the channel that feeds this tasklet
 * @param <State> the per-cell state, held by the runtime and Kryo-serialisable; {@link Void} for none
 * @param <Output> the value emitted through {@link Ctx#emit}; {@link Void} for no output channel
 */
public interface Tasklet<Input, State, Output> {

    /**
     * The tasklet's name and scalar settings, read at registration. Every tasklet class carries
     * it, except a short form ({@code @UnboundedOperator} / {@code @BoundedOperator}), whose
     * tasklet takes the operator's {@code id} as its name.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Spec {

        /**
         * The durable name the tasklet's state is saved under, unique within its operator. Never
         * derived from the class name, so renaming the class keeps the state.
         */
        String name();

        /** Static cell keys. Non-empty together with a written {@link Tasklet#cellsFor}. */
        String[] keys() default {};

        /**
         * Per-cell mailbox capacity, 1 to 1 048 576. Unset ({@code -1}) takes
         * {@code FLEZHA_MAILBOX_CAPACITY}, else 1024.
         */
        int capacity() default -1;

        /** Dynamic-mode soft cap on live cells; inputs for a new key past it are dropped. */
        long maxCells() default 100_000L;

        /**
         * Seconds a dynamic cell may sit idle before it is evicted; {@code 0} keeps every cell.
         * Eviction deletes the cell's state, so a key that reappears starts from nothing.
         */
        long idleTtlSeconds() default 0L;

        /** Per-cell bound on pending timers; one more is refused with {@code runtime.timer_cap_exceeded}. */
        int maxTimersPerCell() default 10_000;

        /** Per-cell change log size, in bytes, before the controller asks for a snapshot. */
        long compactAfterBytes() default 8L * 1024 * 1024;

        /**
         * The version stamped on every record this tasklet writes. Bump it on any non-additive
         * edit to the state or a change record, and read older records through
         * {@link Tasklet#migrateState} and {@link Tasklet#migrateChange}.
         */
        int schemaVersion() default 1;

        /** The oldest schema version still read. {@code 0} means {@link #schemaVersion()}. */
        int minReadableSchemaVersion() default 0;

        /**
         * Serve {@code ctx.now()} from the cell's durable change seq instead of the wall clock.
         * Needs a non-{@link Void} state, and cannot be combined with a written
         * {@link Tasklet#eventTime}.
         */
        boolean logicalClock() default false;
    }

    /**
     * Handles one message. This is where your logic goes.
     *
     * <p>Called once per message, for one cell, and never concurrently for the same cell. It is
     * also re-executed during recovery, so it must produce the same result given the same input.
     *
     * @param input the message, already decoded from the channel that feeds this tasklet
     * @param ctx this invocation's context: the cell's state and its changes, emits, the clock,
     *     replay-stable identifiers and timers
     * @throws TaskletException to fail this invocation. Any output emitted earlier in it is
     *     discarded, and the input climbs its channel's retry ladder. Any other runtime exception
     *     is treated the same way
     */
    void run(Input input, Ctx<State, Output> ctx) throws TaskletException;

    /**
     * Handles a timer this tasklet registered through {@link Ctx#timerAtEventTime} or
     * {@link Ctx#timerAfter}, with the same context {@link #run} gets.
     *
     * <p>{@code firedAt} is the time the timer was scheduled at, never the moment it actually
     * fired, and {@code ctx.now()} equals it, so a replay observes the same value. A failure here
     * climbs the input channel's retry ladder as a failing {@code run} does.
     *
     * <p>The default throws naming the tasklet: registering a timer without handling it is an
     * authoring error.
     */
    default void onTimer(long firedAt, TimerKind kind, Ctx<State, Output> ctx) throws TaskletException {
        throw new UnsupportedOperationException(
                label() + " registered a timer but does not override onTimer(firedAt, kind, ctx)");
    }

    /**
     * The event time, in epoch milliseconds, an input carries. Writing it makes the tasklet's
     * clock event time: {@code ctx.now()} returns this value, and event-time timers fire against
     * it.
     */
    default long eventTime(Input input) {
        throw new UnsupportedOperationException(label() + " does not override eventTime(Input)");
    }

    /**
     * Undoes a reversible side effect of {@link #run} (bounded operators only). The bounded driver
     * calls it only while rolling back an abnormal termination, once per cell that ran, in reverse
     * completion order, with that cell's own outputs and its context. It must be idempotent: a
     * crash mid-rollback may call it again, and after one {@code completedOutput} can be empty.
     * Writing it is what makes the tasklet compensable.
     */
    default void compensate(List<Output> completedOutput, Ctx<State, Output> ctx) throws TaskletException {}

    /**
     * For static cells: which of the {@link Spec#keys()} this input goes to. Each named cell gets
     * its own {@code run}; an empty list drops the input.
     */
    @Nonnull
    default List<String> cellsFor(Input input) {
        return List.of();
    }

    /**
     * Builds a new cell's state from its key, for a state that depends on it. Without it the
     * state class's no-arg constructor builds every new cell's state. A restored cell's snapshot
     * and change log are applied after this returns.
     *
     * @param cellKey the cell's key; empty for a single-cell tasklet
     */
    @Nonnull
    default State initState(Optional<String> cellKey) {
        throw new UnsupportedOperationException(label() + " does not override initState(cellKey)");
    }

    /**
     * Rebuilds the state from a snapshot written at an older {@link Spec#schemaVersion()}, for
     * versions down to {@link Spec#minReadableSchemaVersion()}. Keep the old type in your own
     * source and decode it here. Must be deterministic.
     */
    @Nonnull
    default State migrateState(int fromVersion, byte[] bytes) {
        throw new IllegalStateException(label() + " declares no state migration from schema_version " + fromVersion);
    }

    /** Rebuilds a change written at an older schema version; as {@link #migrateState}, per record. */
    @Nonnull
    default Change<State> migrateChange(int fromVersion, byte[] bytes) {
        throw new IllegalStateException(label() + " declares no change migration from schema_version " + fromVersion);
    }

    /**
     * The types saved state and changes are encoded with, when you want to own their ids.
     *
     * <p>Without it the SDK finds them: the state class, every member class (declared in a class
     * body, at any depth) of this class, a class above it, or the state class that implements
     * {@link Change}, and every non-JDK class their fields reach. A local or anonymous class is not
     * found. Each gets an id derived from its name, so adding a type never moves another; a
     * type nested in this class is named by the {@link Spec#name()} and its path inside the class,
     * so renaming or moving this class keeps the ids. Writing this replaces discovery: the listed
     * types take ids in list order, so only appending is safe. Switching an existing tasklet
     * between the two moves every id, and needs a {@link Spec#schemaVersion()} bump with
     * {@link #migrateState} / {@link #migrateChange}.
     */
    @Nonnull
    default List<Class<?>> kryoTypes() {
        return List.of();
    }

    /** How the default hooks name this tasklet: by its {@link Spec#name()}, else by its class. */
    private String label() {
        Spec spec = getClass().getAnnotation(Spec.class);
        return spec != null ? "tasklet `" + spec.name() + "`" : getClass().getName();
    }
}
