package com.flezha.sdk.testing;

import com.flezha.sdk.runtime.state.KryoCodec;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.ReplayMismatch;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.EmitKeys;
import com.flezha.sdk.tasklet.StableId;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
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
 * Test harness for {@link Tasklet} authors. Drives a Tasklet through a sequence of
 * inputs without standing up the runtime, recording the Changes
 * {@code state.apply}-ed and the values {@code ctx.emit}-ed. Mirrors the
 * Rust SDK's {@code TaskletTestHarness<S>}.
 *
 * <p>{@link #replayEquals()} is the determinism gate: it reconstructs the
 * initial state captured at construction, replays the recorded Changes, and
 * compares the Kryo-serialized bytes. Equality means the Tasklet's state is
 * a deterministic function of the Change log — which is the umbrella's
 * correctness contract.
 */
public final class TaskletTestHarness<Input, State, Output, ChangeT extends Change> {

    private final Tasklet<Input, State, Output, ChangeT, ?, ?> tasklet;
    private State state;
    /** Kryo snapshot of the state at construction, replayed by {@link #replayEquals()}. */
    private byte[] initialStateBytes;

    private final List<ChangeT> recordedChanges = new ArrayList<>();
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

    private TaskletTestHarness(Tasklet<Input, State, Output, ChangeT, ?, ?> tasklet, State initialState) {
        this.tasklet = tasklet;
        captureInitial(initialState);
    }

    /** Snapshot {@code initial} as the replay baseline, before {@code applyInputs} mutates it. */
    private void captureInitial(State initial) {
        this.state = initial;
        // Stateless tasklets journal nothing, so there is no replay baseline to capture
        // (and their `Stateless` state is not Kryo-registered).
        this.initialStateBytes =
                tasklet.changeType() == NoChange.class ? null : new KryoCodec(tasklet.kryoTypes()).serialize(initial);
    }

    /**
     * Static factory. Calls {@code Tasklet.initState(Optional.empty(), Optional.empty())}
     * to seed the harness with a fresh state. Override via
     * {@link #withInitialState(Object)} or {@link #withPayload(Optional, RestoreInitPayload)}.
     */
    @Nonnull
    public static <I, S, O, C extends Change> TaskletTestHarness<I, S, O, C> of(
            @Nonnull Tasklet<I, S, O, C, ?, ?> tasklet) {
        S init = tasklet.initState(Optional.empty(), Optional.empty());
        return new TaskletTestHarness<>(tasklet, init);
    }

    @Nonnull
    public TaskletTestHarness<Input, State, Output, ChangeT> withInitialState(@Nonnull State state) {
        captureInitial(state);
        return this;
    }

    @Nonnull
    public TaskletTestHarness<Input, State, Output, ChangeT> withPayload(
            @Nonnull Optional<String> cellKey, @Nonnull RestoreInitPayload payload) {
        captureInitial(tasklet.initState(cellKey, Optional.of(payload)));
        this.cellKey = cellKey;
        return this;
    }

    /**
     * Treat the output channel as declared {@code .keyed()}: the keyed-emit contract
     * applies, so {@code emitKeyed} is accepted and a key-less {@code emit} falls back
     * to {@link #withCellKey} — or is refused where there is no Cell key.
     */
    @Nonnull
    public TaskletTestHarness<Input, State, Output, ChangeT> withKeyedChannel() {
        this.keyed = true;
        return this;
    }

    /**
     * Stand in for the Cell routed by {@code key}: {@code ctx.cellKey()} serves it,
     * and a keyed-channel emit that supplies no key of its own is recorded under it.
     * Without this the harness is a single-cell Tasklet, where that emit is refused.
     */
    @Nonnull
    public TaskletTestHarness<Input, State, Output, ChangeT> withCellKey(@Nonnull String key) {
        this.cellKey = Optional.of(key);
        return this;
    }

    /** Run the Tasklet against each input, recording state mutations and emits. */
    @Nonnull
    public TaskletTestHarness<Input, State, Output, ChangeT> applyInputs(@Nonnull Iterable<Input> inputs) {
        for (Input input : inputs) {
            StateHandle<State, ChangeT> handle = StateHandle.create(state, recordedChanges, tasklet::applyChange);
            TestTaskletCtx ctx = new TestTaskletCtx(recordedEmits, emitRecords, clockAt(invocation), keyed, cellKey);
            invocation++;
            try {
                tasklet.run(input, handle, ctx);
            } catch (TaskletException e) {
                // Test harness is forgiving — record but keep going.
            }
        }
        return this;
    }

    /** Assert that the recorded Change list equals the supplied list. */
    @Nonnull
    public TaskletTestHarness<Input, State, Output, ChangeT> expectChanges(@Nonnull List<ChangeT> expected) {
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
    public <C> TaskletTestHarness<Input, State, Output, ChangeT> expectEmits(
            @Nonnull Class<C> type, @Nonnull List<C> expected) {
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

    /** Assert that the current state equals the supplied state via {@link Object#equals}. */
    @Nonnull
    public TaskletTestHarness<Input, State, Output, ChangeT> expectState(@Nonnull State expected) {
        if (!expected.equals(state)) {
            throw new AssertionError("state mismatch:\n  expected: " + expected + "\n  actual:   " + state);
        }
        return this;
    }

    /**
     * Imperative replay: start from {@code initial}, fold the change log
     * via {@code Tasklet.applyChange}, and return the resulting state. Useful
     * for cross-checks beyond {@link #replayEquals()}.
     */
    @Nonnull
    public State replay(@Nonnull State initial, @Nonnull List<ChangeT> log) {
        State s = initial;
        for (ChangeT c : log) {
            tasklet.applyChange(s, c);
        }
        return s;
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
        // Stateless tasklets have no journaled state — replay is vacuously equal.
        if (initialStateBytes == null) {
            return Optional.empty();
        }
        KryoCodec codec = new KryoCodec(tasklet.kryoTypes());
        State fresh = (State) codec.deserialize(initialStateBytes, state.getClass());
        for (ChangeT c : recordedChanges) {
            tasklet.applyChange(fresh, c);
        }
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
    public List<ChangeT> recordedChanges() {
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
    public TaskletTestHarness<Input, State, Output, ChangeT> withWallClock(long fixedMillis) {
        clockScript.clear();
        clockScript.add(fixedMillis);
        return this;
    }

    /** Serve one clock value per successive invocation; the last value repeats if inputs outrun it. */
    @Nonnull
    public TaskletTestHarness<Input, State, Output, ChangeT> withWallClockScript(long... millis) {
        clockScript.clear();
        for (long m : millis) {
            clockScript.add(m);
        }
        return this;
    }

    private long clockAt(int n) {
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
        State fresh = tasklet.initState(Optional.empty(), Optional.empty());
        List<ChangeT> replayChanges = new ArrayList<>();
        int n = 0;
        for (Input input : inputs) {
            StateHandle<State, ChangeT> handle = StateHandle.create(fresh, replayChanges, tasklet::applyChange);
            TestTaskletCtx ctx = new TestTaskletCtx(replayEmits, replayRecords, clockAt(n++), keyed, cellKey);
            try {
                tasklet.run(input, handle, ctx);
            } catch (TaskletException e) {
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

    /** Internal {@link TaskletCtx} that records emits without invoking the wire path. */
    static final class TestTaskletCtx implements TaskletCtx {

        /** Stand-in Cell identity: the harness runs one Tasklet on one Cell. */
        private static final byte[] IDENTITY = "harness/op/key".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        private final List<Object> recorded;
        private final List<EmitRecord> records;
        private final long now;
        private final boolean keyed;
        private final Optional<String> cellKey;
        private int stableIdOrdinal;

        TestTaskletCtx(
                List<Object> recorded, List<EmitRecord> records, long now, boolean keyed, Optional<String> cellKey) {
            this.recorded = recorded;
            this.records = records;
            this.now = now;
            this.keyed = keyed;
            this.cellKey = cellKey;
        }

        /**
         * The single record funnel: {@link EmitKeys} resolves the key the runtime would
         * carry — the shared definition is what keeps a harness-green test honest — before
         * anything is recorded. {@code key} is {@code null} for "none supplied";
         * {@link EmitRecord} keeps the empty string for "none carried".
         */
        private void record(String channel, @Nullable String key, Object value, Map<String, String> meta)
                throws TaskletException {
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

        /** No-op: the harness drives one Tasklet directly, with no bounded instance to end. */
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
        public <C> void emit(@Nonnull C value) throws TaskletException {
            record("", null, value, Map.of());
        }

        @Override
        public <C> void emit(@Nonnull String channel, @Nonnull C value) throws TaskletException {
            record(channel, null, value, Map.of());
        }

        @Override
        public <C> void emitWithMeta(@Nonnull C value, @Nonnull Map<String, String> meta) throws TaskletException {
            record("", null, value, meta);
        }

        @Override
        public <C> void emitWithMeta(@Nonnull String channel, @Nonnull C value, @Nonnull Map<String, String> meta)
                throws TaskletException {
            record(channel, null, value, meta);
        }

        @Override
        public <C> void emitKeyed(@Nonnull String key, @Nonnull C value) throws TaskletException {
            record("", key, value, Map.of());
        }

        @Override
        public <C> void emitKeyedWithMeta(@Nonnull String key, @Nonnull C value, @Nonnull Map<String, String> meta)
                throws TaskletException {
            record("", key, value, meta);
        }

        /** Mirror the runtime: a user key in the runtime's namespace is refused, not dropped. */
        private static void rejectReserved(Map<String, String> meta) throws TaskletException {
            for (String k : meta.keySet()) {
                if (k.startsWith(TaskletCtx.RESERVED_METADATA_PREFIX)) {
                    throw new TaskletException.ReservedMetadataKey(k);
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
