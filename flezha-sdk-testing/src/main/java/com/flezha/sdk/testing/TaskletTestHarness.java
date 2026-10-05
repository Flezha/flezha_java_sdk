package com.flezha.sdk.testing;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.runtime.relay.TaskletModel;
import com.flezha.sdk.runtime.state.ClockKind;
import com.flezha.sdk.runtime.state.KryoCodec;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.ReplayMismatch;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.EmitKeys;
import com.flezha.sdk.tasklet.StableId;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Test harness for {@link Tasklet} authors. Drives the class you wrote through a sequence of
 * inputs without standing up the runtime, recording the changes passed to {@code ctx.change} and
 * the values passed to {@code ctx.emit}. It reads the class exactly as registration does: its
 * initial state, its clock and the types its state is encoded with. Mirrors the Rust SDK's
 * {@code TaskletTestHarness<S>}.
 *
 * <p>{@link #replayEquals()} is the determinism gate: it reconstructs the
 * initial state captured at construction, replays the recorded Changes, and
 * compares the Kryo-serialized bytes. Equality means the Tasklet's state is
 * a deterministic function of the Change log — which is the umbrella's
 * correctness contract.
 */
public final class TaskletTestHarness<Input, State, Output> {

    private final Tasklet<Input, State, Output> tasklet;
    private final TaskletModel model;
    private final KryoCodec codec;
    private State state;
    /** Kryo snapshot of the state at construction, replayed by {@link #replayEquals()}. */
    private byte[] initialStateBytes;

    private final List<Change<State>> recordedChanges = new ArrayList<>();
    private final List<Object> recordedEmits = new ArrayList<>();
    private final List<EmitRecord> emitRecords = new ArrayList<>();

    /** Injected clock values, one per successive invocation; empty = ambient wall clock. */
    private final List<Long> clockScript = new ArrayList<>();

    /** Treat the output channel as {@code .keyed()}, so the keyed-emit contract applies. */
    private boolean keyed;

    /**
     * The key the Cell under test was routed by, served to {@code ctx} exactly as the
     * runtime serves a keyed Cell's own key. Empty stands for a single-cell
     * Tasklet, where a keyed channel still refuses a key-less emit.
     */
    private Optional<String> cellKey = Optional.empty();

    private int invocation;

    /**
     * One recorded emit: what was emitted, on which channel, under which key (empty
     * for a non-keyed emit), with which user metadata.
     */
    public record EmitRecord(String channel, String key, Object value, Map<String, String> metadata) {}

    private TaskletTestHarness(Tasklet<Input, State, Output> tasklet, TaskletModel model) {
        this.tasklet = tasklet;
        this.model = model;
        this.codec = model.codec(model.kryoTypes());
        captureInitial(initialState(Optional.empty()));
    }

    /** Snapshot {@code initial} as the replay baseline, before {@code applyInputs} mutates it. */
    private void captureInitial(State initial) {
        this.state = initial;
        // A Void state journals nothing, so there is no replay baseline to capture.
        this.initialStateBytes = model.stateless() ? null : codec.serialize(initial);
    }

    @SuppressWarnings("unchecked")
    private State initialState(Optional<String> key) {
        return (State) model.initialState(tasklet, key);
    }

    /**
     * A harness over {@code tasklet}, starting from the state registration would give a new
     * single cell: the state class's no-arg constructor, or the class's written
     * {@code initState(Optional.empty())}. Override via {@link #withInitialState(Object)} or
     * {@link #withPayload(Optional, RestoreInitPayload)}.
     *
     * @throws FlezhaException naming every refusal registration would raise for the class, in
     *     {@code details.issues}
     */
    @Nonnull
    public static <I, S, O> TaskletTestHarness<I, S, O> of(@Nonnull Tasklet<I, S, O> tasklet) {
        TaskletModel model = TaskletModel.read(tasklet.getClass(), tasklet);
        if (!model.errors().isEmpty()) {
            throw FlezhaException.ofIssues(model.errors());
        }
        return new TaskletTestHarness<>(tasklet, model);
    }

    @Nonnull
    public TaskletTestHarness<Input, State, Output> withInitialState(@Nonnull State state) {
        captureInitial(state);
        return this;
    }

    /**
     * Restore the cell keyed {@code cellKey} as the runtime does: its initial state, then the
     * payload's snapshot when there is one, then each change of its change log through
     * {@code applyTo}.
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public TaskletTestHarness<Input, State, Output> withPayload(
            @Nonnull Optional<String> cellKey, @Nonnull RestoreInitPayload payload) {
        State restored = payload.snapshotBytes().length > 0
                ? (State) codec.deserialize(payload.snapshotBytes(), Object.class)
                : initialState(cellKey);
        if (payload.changeLogBytes().length > 0) {
            replay(restored, (List<Change<State>>) codec.deserialize(payload.changeLogBytes(), List.class));
        }
        captureInitial(restored);
        this.cellKey = cellKey;
        return this;
    }

    /**
     * Treat the output channel as declared {@code .keyed()}: the keyed-emit contract
     * applies, so {@code emitKeyed} is accepted and a key-less {@code emit} falls back
     * to {@link #withCellKey} — or is refused where there is no Cell key.
     */
    @Nonnull
    public TaskletTestHarness<Input, State, Output> withKeyedChannel() {
        this.keyed = true;
        return this;
    }

    /**
     * Stand in for the Cell routed by {@code key}: {@code ctx.cellKey()} serves it,
     * and a keyed-channel emit that supplies no key of its own is recorded under it.
     * Without this the harness is a single-cell Tasklet, where that emit is refused.
     */
    @Nonnull
    public TaskletTestHarness<Input, State, Output> withCellKey(@Nonnull String key) {
        this.cellKey = Optional.of(key);
        return this;
    }

    /** Run the Tasklet against each input, recording state mutations and emits. */
    @Nonnull
    public TaskletTestHarness<Input, State, Output> applyInputs(@Nonnull Iterable<Input> inputs) {
        for (Input input : inputs) {
            TestCtx ctx = new TestCtx(state, recordedChanges, recordedEmits, emitRecords, clockAt(invocation, input));
            invocation++;
            int recorded = recordedChanges.size();
            try {
                tasklet.run(input, ctx);
            } catch (TaskletException | FlezhaException e) {
                // Test harness is forgiving — record but keep going.
            }
            // Encode what the invocation recorded, as the runtime's flush does, so a change the
            // codec cannot write fails here, with UnregisteredClass, rather than first in production.
            codec.serializeAll(recordedChanges.subList(recorded, recordedChanges.size()));
        }
        return this;
    }

    /** Assert that the recorded Change list equals the supplied list. */
    @Nonnull
    public TaskletTestHarness<Input, State, Output> expectChanges(@Nonnull List<? extends Change<State>> expected) {
        if (!recordedChanges.equals(expected)) {
            throw new AssertionError("changes mismatch:\n  expected: " + expected + "\n  actual:   " + recordedChanges);
        }
        return this;
    }

    /**
     * Assert that recorded emits of the given type equal the supplied list.
     * Filters {@code recordedEmits} by type so harnesses with multiple emit
     * channel types remain testable.
     */
    @SuppressWarnings("unchecked")
    @Nonnull
    public <C> TaskletTestHarness<Input, State, Output> expectEmits(@Nonnull Class<C> type, @Nonnull List<C> expected) {
        List<C> actual = new ArrayList<>();
        for (Object emit : recordedEmits) {
            if (type.isInstance(emit)) {
                actual.add((C) emit);
            }
        }
        if (!actual.equals(expected)) {
            throw new AssertionError("emits mismatch (" + type.getSimpleName() + "):\n" + "  expected: " + expected
                    + "\n  actual:   " + actual);
        }
        return this;
    }

    /**
     * Assert that the current state equals the supplied state, compared as the bytes the state is
     * saved as, so a state class need not override {@link Object#equals}.
     */
    @Nonnull
    public TaskletTestHarness<Input, State, Output> expectState(@Nonnull State expected) {
        if (!Arrays.equals(codec.serialize(expected), codec.serialize(state))) {
            throw new AssertionError("state mismatch:\n  expected: " + expected + "\n  actual:   " + state);
        }
        return this;
    }

    /**
     * Imperative replay: start from {@code initial}, fold the change log
     * through each change's {@code applyTo}, and return the resulting state.
     * Useful for cross-checks beyond {@link #replayEquals()}.
     */
    @Nonnull
    public State replay(@Nonnull State initial, @Nonnull List<? extends Change<State>> log) {
        for (Change<State> c : log) {
            c.applyTo(initial);
        }
        return initial;
    }

    /**
     * Determinism gate. Reconstructs the initial state captured at
     * construction (the snapshot the runtime would restore), folds the
     * recorded Change log, then compares the Kryo bytes of the harness's
     * current state against the replayed state. Returns
     * {@code Optional.empty()} on byte-equality (deterministic Tasklet) or
     * {@link ReplayMismatch} carrying the diagnostic bytes otherwise.
     */
    @SuppressWarnings("unchecked")
    @Nonnull
    public Optional<ReplayMismatch> replayEquals() {
        // A Void state journals nothing, so replay is vacuously equal.
        if (initialStateBytes == null) {
            return Optional.empty();
        }
        State fresh = (State) codec.deserialize(initialStateBytes, state.getClass());
        replay(fresh, recordedChanges);
        byte[] expected = codec.serialize(state);
        byte[] actual = codec.serialize(fresh);
        if (Arrays.equals(expected, actual)) {
            return Optional.empty();
        }
        return Optional.of(new ReplayMismatch(expected, actual));
    }

    /** Read-only view of the harness state for direct assertions. */
    @Nonnull
    public State state() {
        return state;
    }

    /** Read-only view of the recorded Change log. */
    @Nonnull
    public List<Change<State>> recordedChanges() {
        return List.copyOf(recordedChanges);
    }

    /** Read-only view of the recorded emit log (mixed types). */
    @Nonnull
    public List<Object> recordedEmits() {
        return List.copyOf(recordedEmits);
    }

    /** Structured emit log: channel, key, value and metadata per emit. */
    @Nonnull
    public List<EmitRecord> emitRecords() {
        return List.copyOf(emitRecords);
    }

    /** Pin {@code ctx.now()} to a constant for every invocation. */
    @Nonnull
    public TaskletTestHarness<Input, State, Output> withWallClock(long fixedMillis) {
        clockScript.clear();
        clockScript.add(fixedMillis);
        return this;
    }

    /** Serve one clock value per successive invocation; the last value repeats if inputs outrun it. */
    @Nonnull
    public TaskletTestHarness<Input, State, Output> withWallClockScript(long... millis) {
        clockScript.clear();
        for (long m : millis) {
            clockScript.add(m);
        }
        return this;
    }

    /**
     * {@code ctx.now()} for the {@code n}-th invocation, served as the runtime serves the class's
     * clock: the input's event time, the invocation's change seq, or the (injected) wall clock.
     */
    private long clockAt(int n, Input input) {
        if (model.clock() == ClockKind.EVENT_TIME) {
            return tasklet.eventTime(input);
        }
        if (model.clock() == ClockKind.LOGICAL) {
            return n + 1L;
        }
        if (clockScript.isEmpty()) {
            return System.currentTimeMillis();
        }
        return clockScript.get(Math.min(n, clockScript.size() - 1));
    }

    /**
     * Output-side determinism gate — the sibling of {@link #replayEquals()}.
     *
     * <p>Re-runs the recorded inputs against a fresh baseline and compares the
     * resulting emit sequence. Equal emits mean the Tasklet's OUTPUT is a
     * deterministic function of its inputs — which {@code replayEquals} alone does
     * not prove: a Tasklet can rebuild identical state while emitting a
     * different key or timestamp each run, and only a keyed sink's idempotence
     * depends on that not happening.
     *
     * <p>A Tasklet that reads {@code System.currentTimeMillis()} or
     * {@code UUID.randomUUID()} instead of {@code ctx.now()} / {@code ctx.stableId()}
     * is exactly what this catches.
     *
     * @return empty when the replayed emits match, else a diagnostic
     */
    @Nonnull
    public Optional<String> replayEmitsEqual(@Nonnull Iterable<Input> inputs) {
        List<EmitRecord> original = List.copyOf(emitRecords);

        // Replay against a fresh baseline, with the clock served identically.
        List<Object> replayEmits = new ArrayList<>();
        List<EmitRecord> replayRecords = new ArrayList<>();
        State fresh = initialState(Optional.empty());
        List<Change<State>> replayChanges = new ArrayList<>();
        int n = 0;
        for (Input input : inputs) {
            TestCtx ctx = new TestCtx(fresh, replayChanges, replayEmits, replayRecords, clockAt(n, input));
            n++;
            try {
                tasklet.run(input, ctx);
            } catch (TaskletException | FlezhaException e) {
                // Mirror applyInputs: record what happened and keep going.
            }
        }

        if (original.size() != replayRecords.size()) {
            return Optional.of(
                    "emit count diverged on replay: original " + original.size() + ", replay " + replayRecords.size());
        }
        for (int i = 0; i < original.size(); i++) {
            if (!original.get(i).equals(replayRecords.get(i))) {
                return Optional.of(
                        "emit " + i + " diverged on replay:\n  original: " + original.get(i)
                                + "\n  replay:   " + replayRecords.get(i)
                                + "\n(does the Tasklet read ambient time or randomness instead of ctx.now() / ctx.stableId()?)");
            }
        }
        return Optional.empty();
    }

    /** The harness's {@link Ctx}: records changes and emits without invoking the wire path. */
    final class TestCtx implements Ctx<State, Output> {

        /** Stand-in Cell identity: the harness runs one Tasklet on one Cell. */
        private static final byte[] IDENTITY = "harness/op/key".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        private final State cellState;
        private final List<Change<State>> changes;
        private final List<Object> recorded;
        private final List<EmitRecord> records;
        private final long now;
        private int stableIdOrdinal;

        TestCtx(
                State cellState,
                List<Change<State>> changes,
                List<Object> recorded,
                List<EmitRecord> records,
                long now) {
            this.cellState = cellState;
            this.changes = changes;
            this.recorded = recorded;
            this.records = records;
            this.now = now;
        }

        @Override
        public State state() {
            return cellState;
        }

        @Override
        public void change(@Nonnull Change<State> change) {
            model.record(change, cellState, changes);
        }

        /**
         * The single record funnel: {@link EmitKeys} resolves the key the runtime would
         * carry — the shared definition is what keeps a harness-green test honest — before
         * anything is recorded. {@code key} is {@code null} for "none supplied";
         * {@link EmitRecord} keeps the empty string for "none carried".
         */
        private void record(String channel, @Nullable String key, Object value, Map<String, String> meta) {
            key = EmitKeys.resolve(channelName(channel), keyed, key, cellKey);
            rejectReserved(meta);
            recorded.add(value);
            records.add(new EmitRecord(channel, key == null ? "" : key, value, Map.copyOf(meta)));
        }

        /** The harness's default channel has no declared name; refusals still have to name one. */
        private static String channelName(String channel) {
            return channel.isEmpty() ? "<default>" : channel;
        }

        @Override
        public long now() {
            return now;
        }

        /** No-op: the harness drives one Tasklet directly, with no durable interval to close. */
        @Override
        public void checkpoint() {}

        /** No-op: the harness drives one Tasklet directly, with no steps instance to end. */
        @Override
        public void complete(@Nonnull byte[] result) {}

        @Override
        @Nonnull
        public StableId stableId() {
            return StableId.derive(IDENTITY, 0L, stableIdOrdinal++);
        }

        @Override
        @Nonnull
        public Optional<String> cellKey() {
            return cellKey;
        }

        @Override
        public void emit(@Nonnull Output value) {
            record("", null, value, Map.of());
        }

        @Override
        public <C> void emit(@Nonnull String channel, @Nonnull C value) {
            record(channel, null, value, Map.of());
        }

        @Override
        public void emitWithMeta(@Nonnull Output value, @Nonnull Map<String, String> meta) {
            record("", null, value, meta);
        }

        @Override
        public <C> void emitWithMeta(@Nonnull String channel, @Nonnull C value, @Nonnull Map<String, String> meta) {
            record(channel, null, value, meta);
        }

        @Override
        public void emitKeyed(@Nonnull String key, @Nonnull Output value) {
            record("", key, value, Map.of());
        }

        @Override
        public void emitKeyedWithMeta(@Nonnull String key, @Nonnull Output value, @Nonnull Map<String, String> meta) {
            record("", key, value, meta);
        }

        /**
         * No-op: a part never reaches a sink, so it is not an emit to record, and the harness
         * encodes no emit either, so there is no encoding to check.
         */
        @Override
        public void part(@Nonnull Output value) {}

        /** Mirror the runtime: a user key in the runtime's namespace is refused, not dropped. */
        private static void rejectReserved(Map<String, String> meta) {
            for (String k : meta.keySet()) {
                if (k.startsWith(Ctx.RESERVED_METADATA_PREFIX)) {
                    throw FlezhaException.of("runtime.reserved_metadata_key", Map.of("key", k));
                }
            }
        }
    }

    /** Construct the metadata map idiomatically; lets users inline Map.of(...) calls. */
    @Nonnull
    public static Map<String, String> meta(@Nonnull String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }
}
