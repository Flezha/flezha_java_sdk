package com.flezha.sdk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.runtime.state.KryoCodec;
import com.flezha.sdk.runtime.state.KryoTypes;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.StateSerializeException;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import com.flezha.sdk.testing.HarnessBehaviourTest.CounterState.CounterChange;
import com.flezha.sdk.testing.HarnessBehaviourTest.CounterState.DriftingChange;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@link TaskletTestHarness} shows an author, in three parts: the key a
 * record ships under, the determinism gates over state ({@code replayEquals})
 * and over output ({@code replayEmitsEqual}), and the state-mutation bypass
 * only {@code replayEquals} can see.
 *
 * <p>{@code replayEquals} alone is not enough: a Tasklet can rebuild
 * identical state on replay while emitting a different key or timestamp each
 * run — and a keyed sink's idempotence depends on exactly that not happening.
 *
 * <p>Java's bypass is structural: {@code ctx.state()} returns the live State
 * object, so {@code ctx.state().field = x} compiles, runs, and is NEVER
 * journaled as a Change. Rust forecloses it by not implementing
 * {@code DerefMut}; Java cannot, because {@code state()} must return a readable
 * object, so the opt-in gate is the detection this SDK provides instead.
 */
class HarnessBehaviourTest {

    /** The counters' state, with the changes nested where the codec finds them. */
    public static final class CounterState {
        long value;

        public record CounterChange(long delta) implements Change<CounterState> {
            @Override
            public void applyTo(CounterState state) {
                state.value += delta;
            }
        }

        /**
         * A change whose {@code applyTo} reads a global counter, which differs across replays.
         * This drives a {@code replayEquals} mismatch.
         */
        public record DriftingChange(long delta) implements Change<CounterState> {
            private static final AtomicLong GLOBAL = new AtomicLong();

            @Override
            public void applyTo(CounterState state) {
                state.value += GLOBAL.incrementAndGet();
            }
        }
    }

    /** Deterministic Tasklet: its change adds the input. */
    @Tasklet.Spec(name = "counter")
    public static final class DeterministicCounterTasklet implements Tasklet<Long, CounterState, Void> {
        @Override
        public void run(Long input, Ctx<CounterState, Void> ctx) {
            ctx.change(new CounterChange(input));
        }
    }

    /** Non-deterministic Tasklet: its change applies differently on every replay. */
    @Tasklet.Spec(name = "counter_nd")
    public static final class NonDeterministicCounterTasklet implements Tasklet<Long, CounterState, Void> {
        @Override
        public void run(Long input, Ctx<CounterState, Void> ctx) {
            ctx.change(new DriftingChange(input));
        }
    }

    /** Emits on what the harness may declare keyed, without supplying a key of its own. */
    @Tasklet.Spec(name = "key_less_emit")
    public static final class KeyLessEmitTasklet implements Tasklet<Long, Void, String> {
        @Override
        public void run(Long input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("v" + input);
        }
    }

    @Test
    @DisplayName("Harness asserts on recorded Changes, and takes the author's class")
    void harnessAssertsOnRecordedChanges() {
        TaskletTestHarness<Long, CounterState, Void> h = TaskletTestHarness.of(new DeterministicCounterTasklet())
                .applyInputs(List.of(5L, 3L))
                .expectChanges(List.of(new CounterChange(5), new CounterChange(3)));

        CounterState expected = new CounterState();
        expected.value = 8;
        h.expectState(expected);
    }

    @Test
    @DisplayName("Harness replays for determinism check; Record State works with discovered types")
    void harnessReplaysForDeterminismCheck() {
        TaskletTestHarness<Long, CounterState, Void> h =
                TaskletTestHarness.of(new DeterministicCounterTasklet()).applyInputs(List.of(1L, 2L));
        assertThat(h.replayEquals()).isEmpty();
    }

    @Test
    @DisplayName("Harness detects non-deterministic applyTo")
    void harnessDetectsNonDeterministicApplyTo() {
        assertThat(TaskletTestHarness.of(new NonDeterministicCounterTasklet())
                        .applyInputs(List.of(1L))
                        .replayEquals())
                .isPresent();
    }

    @Test
    @DisplayName("A Void-state tasklet keeps no replay baseline")
    void aVoidStateTaskletKeepsNoReplayBaseline() {
        TaskletTestHarness<Long, Void, String> h =
                TaskletTestHarness.of(new KeyLessEmitTasklet()).applyInputs(List.of(1L));
        assertThat(h.state()).isNull();
        assertThat(h.replayEquals()).isEmpty();
    }

    @Test
    @DisplayName("withInitialState baseline is honored by replayEquals")
    void withInitialStateBaselineIsHonored() {
        CounterState start = new CounterState();
        start.value = 10;
        TaskletTestHarness<Long, CounterState, Void> h = TaskletTestHarness.of(new DeterministicCounterTasklet())
                .withInitialState(start)
                .applyInputs(List.of(1L, 2L));
        assertThat(h.state().value).isEqualTo(13);
        assertThat(h.replayEquals()).isEmpty();
    }

    @Test
    @DisplayName("withPayload restores a snapshot, then folds the change log through applyTo")
    void withPayloadRestoresSnapshotThenChanges() {
        TaskletTestHarness<Long, CounterState, Void> source =
                TaskletTestHarness.of(new DeterministicCounterTasklet()).applyInputs(List.of(4L));
        KryoCodec codec = KryoCodec.named(KryoTypes.ids(
                List.of(CounterState.class, CounterChange.class), DeterministicCounterTasklet.class, "counter"));
        byte[] snapshot = codec.serialize(source.state());
        byte[] log = codec.serialize(new java.util.ArrayList<>(List.of(new CounterChange(2), new CounterChange(1))));

        TaskletTestHarness<Long, CounterState, Void> restored = TaskletTestHarness.of(new DeterministicCounterTasklet())
                .withPayload(Optional.empty(), new RestoreInitPayload(snapshot, log));

        assertThat(restored.state().value).isEqualTo(7);
    }

    @Test
    @DisplayName("An event-time tasklet reads its time from the input")
    void anEventTimeTaskletReadsItsTimeFromTheInput() {
        TaskletTestHarness<Long, Void, String> h =
                TaskletTestHarness.of(new EventTimeTask()).withWallClock(1L).applyInputs(List.of(5000L));
        assertThat(h.recordedEmits()).containsExactly("now=5000");
    }

    @Test
    @DisplayName("a logical-clock tasklet reads the invocation's change seq")
    void aLogicalClockTaskletReadsTheChangeSeq() {
        TaskletTestHarness<Long, CounterState, String> h =
                TaskletTestHarness.of(new LogicalClockTask()).applyInputs(List.of(7L, 7L));
        assertThat(h.recordedEmits()).containsExactly("now=1", "now=2");
    }

    @Test
    @DisplayName("State referencing an unregistered class throws a diagnostic naming it")
    void stateReferencingAnUnregisteredClassThrowsADiagnostic() {
        TaskletTestHarness<Long, OrderBookState, Void> h =
                TaskletTestHarness.of(new OrderBook()).applyInputs(List.of(3L));

        assertThatThrownBy(h::replayEquals)
                .isInstanceOf(StateSerializeException.UnregisteredClass.class)
                .hasMessageContaining(Level.class.getName());
    }

    @Test
    @DisplayName("a change declared outside the tasklet and its state fails in the harness as it fails live")
    void anUndiscoveredChangeFailsInTheHarness() {
        assertThatThrownBy(() -> TaskletTestHarness.of(new OutsideChangeTask()).applyInputs(List.of(1L)))
                .isInstanceOf(StateSerializeException.UnregisteredClass.class)
                .hasMessageContaining(Bumped.class.getName())
                .hasMessageContaining("kryoTypes()");
    }

    @Test
    @DisplayName("a lambda change fails in the harness as it fails live")
    void aLambdaChangeFailsInTheHarness() {
        assertThatThrownBy(() -> TaskletTestHarness.of(new LambdaChangeTask()).applyInputs(List.of(1L)))
                .isInstanceOf(StateSerializeException.UnregisteredClass.class)
                .hasMessageContaining("Lambda");
    }

    /** Declared beside the tasklet, not inside it or its state, so discovery never sees it. */
    public record Bumped(long delta) implements Change<CounterState> {
        @Override
        public void applyTo(CounterState state) {
            state.value += delta;
        }
    }

    @Tasklet.Spec(name = "outside_change")
    public static final class OutsideChangeTask implements Tasklet<Long, CounterState, Void> {
        @Override
        public void run(Long input, Ctx<CounterState, Void> ctx) {
            ctx.change(new Bumped(input));
        }
    }

    @Tasklet.Spec(name = "lambda_change")
    public static final class LambdaChangeTask implements Tasklet<Long, CounterState, Void> {
        @Override
        public void run(Long input, Ctx<CounterState, Void> ctx) {
            ctx.change(state -> state.value += input);
        }
    }

    @Tasklet.Spec(name = "event_time")
    public static final class EventTimeTask implements Tasklet<Long, Void, String> {
        @Override
        public long eventTime(Long input) {
            return input;
        }

        @Override
        public void run(Long input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("now=" + ctx.now());
        }
    }

    @Tasklet.Spec(name = "logical", logicalClock = true)
    public static final class LogicalClockTask implements Tasklet<Long, CounterState, String> {
        @Override
        public void run(Long input, Ctx<CounterState, String> ctx) throws TaskletException {
            ctx.change(new CounterChange(input));
            ctx.emit("now=" + ctx.now());
        }
    }

    public record Level(long quantity) {}

    public static final class OrderBookState {
        public java.util.Map<Long, Level> levels = new java.util.HashMap<>();
    }

    public record LevelAdded(long price) implements Change<OrderBookState> {
        @Override
        public void applyTo(OrderBookState state) {
            state.levels.put(price, new Level(1));
        }
    }

    /** Lists its types by hand and leaves out {@link Level}, which its state reaches. */
    @Tasklet.Spec(name = "order_book")
    public static final class OrderBook implements Tasklet<Long, OrderBookState, Void> {
        @Override
        public List<Class<?>> kryoTypes() {
            return List.of(OrderBookState.class, LevelAdded.class);
        }

        @Override
        public void run(Long input, Ctx<OrderBookState, Void> ctx) {
            ctx.change(new LevelAdded(input));
        }
    }

    /**
     * fails when: a key-less emit inside a keyed Cell stops carrying that Cell's own
     * key — the harness would stop showing authors the key their records actually ship
     * under, which is the drift this default exists to remove.
     */
    @Test
    void aKeyedCellRecordsItsOwnKeyOnAKeyLessEmit() {
        TaskletTestHarness<Long, Void, String> h = TaskletTestHarness.of(new KeyLessEmitTasklet())
                .withKeyedChannel()
                .withCellKey("BTC/USD")
                .applyInputs(List.of(1L));

        assertThat(h.emitRecords()).hasSize(1);
        assertThat(h.emitRecords().get(0).key()).isEqualTo("BTC/USD");
        assertThat(h.replayEmitsEqual(List.of(1L))).isEmpty();
    }

    /**
     * fails when: a single-cell Tasklet's key-less emit is accepted on a keyed
     * channel — it has no key to default to, so the record would ship keyless.
     */
    @Test
    void aSingleCellKeyLessEmitOnAKeyedChannelIsRefused() {
        TaskletTestHarness<Long, Void, String> h = TaskletTestHarness.of(new KeyLessEmitTasklet())
                .withKeyedChannel()
                .applyInputs(List.of(1L));

        assertThat(h.emitRecords()).as("the refusal leaves nothing recorded").isEmpty();
    }

    /** A harness that declares no keyed channel is unchanged: no key, no refusal. */
    @Test
    void aNonKeyedChannelRecordsNoKey() {
        TaskletTestHarness<Long, Void, String> h = TaskletTestHarness.of(new KeyLessEmitTasklet())
                .withCellKey("BTC/USD")
                .applyInputs(List.of(1L));

        assertThat(h.emitRecords()).hasSize(1);
        assertThat(h.emitRecords().get(0).key()).isEmpty();
    }

    @Test
    void deterministicTaskletReplayEqualsPasses() {
        TaskletTestHarness<Long, CounterState, Void> h =
                TaskletTestHarness.of(new DeterministicCounterTasklet()).applyInputs(List.of(1L, 2L, 3L));
        assertThat(h.recordedChanges()).hasSize(3);
        assertThat(h.replayEquals()).isEmpty();
    }

    @Test
    void nonDeterministicTaskletReplayEqualsReportsMismatch() {
        TaskletTestHarness<Long, CounterState, Void> h =
                TaskletTestHarness.of(new NonDeterministicCounterTasklet()).applyInputs(List.of(1L, 2L, 3L));
        assertThat(h.replayEquals()).isPresent();
    }

    @Test
    @DisplayName("a deterministic Tasklet replays identical emits")
    void deterministicTaskletReplaysEqualEmits() {
        List<String> inputs = List.of("a", "b");
        TaskletTestHarness<String, Void, String> harness =
                TaskletTestHarness.of(new DeterministicTask()).withWallClock(1_700_000_000_000L);

        harness.applyInputs(inputs);

        assertThat(harness.replayEmitsEqual(inputs)).isEmpty();
    }

    @Test
    @DisplayName("a Tasklet reading ambient time is caught")
    void ambientTimeTaskletIsCaught() {
        List<String> inputs = List.of("a");
        TaskletTestHarness<String, Void, String> harness =
                TaskletTestHarness.of(new AmbientTimeTask()).withWallClock(1_700_000_000_000L);

        harness.applyInputs(inputs);

        // The replay emits a different nanoTime, so the output is not a function
        // of the input — which is precisely the defect.
        assertThat(harness.replayEmitsEqual(inputs)).isPresent();
    }

    @Test
    @DisplayName("a scripted clock serves one value per invocation")
    void scriptedClockAdvancesPerInvocation() {
        TaskletTestHarness<String, Void, String> harness =
                TaskletTestHarness.of(new DeterministicTask()).withWallClockScript(1000L, 2000L, 3000L);

        harness.applyInputs(List.of("a", "b", "c"));

        assertThat(harness.recordedEmits()).containsExactly("a@1000", "b@2000", "c@3000");
    }

    @Test
    @DisplayName("a keyed emit records its key")
    void keyedEmitRecordsItsKey() {
        TaskletTestHarness<String, Void, String> harness =
                TaskletTestHarness.of(new KeyedTask()).withKeyedChannel().withWallClock(1L);

        harness.applyInputs(List.of("BTC"));

        assertThat(harness.emitRecords()).hasSize(1);
        assertThat(harness.emitRecords().get(0).key()).isEqualTo("BTC");
    }

    /** Reads time via ctx.now() — replayable. */
    @Tasklet.Spec(name = "deterministic_op")
    public static final class DeterministicTask implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input + "@" + ctx.now());
        }
    }

    /** Reads ambient time — NOT replayable. */
    @Tasklet.Spec(name = "ambient_time_op")
    public static final class AmbientTimeTask implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input + "@" + System.nanoTime());
        }
    }

    @Tasklet.Spec(name = "keyed_op")
    public static final class KeyedTask implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emitKeyed(input, "payload");
        }
    }

    @Test
    @DisplayName("a direct field write bypasses the change log and is caught only by replayEquals()")
    void directMutationIsDroppedAndOnlyReplayEqualsCatchesIt() {
        TaskletTestHarness<String, Counter, String> harness =
                TaskletTestHarness.of(new BypassingTask()).withWallClock(1L);

        harness.applyInputs(List.of("a", "b", "c"));

        // The live object WAS mutated — the bypass "works" in memory.
        assertThat(harness.state().count).isEqualTo(3L);
        // ...yet nothing was journaled: a restore rebuilds from snapshot + change
        // log and would come back at 0. This is the silent data loss.
        assertThat(harness.recordedChanges()).isEmpty();
        // The one thing that catches it: replaying the (empty) change log rebuilds
        // count=0, which does not match the live count=3.
        assertThat(harness.replayEquals()).isPresent();
    }

    @Test
    @DisplayName("the same Tasklet using ctx.change() is journaled and replay-clean")
    void applyPathIsJournaledAndReplaySafe() {
        TaskletTestHarness<String, Counter, String> harness =
                TaskletTestHarness.of(new JournalingTask()).withWallClock(1L);

        harness.applyInputs(List.of("a", "b", "c"));

        assertThat(harness.state().count).isEqualTo(3L);
        assertThat(harness.recordedChanges()).hasSize(3);
        assertThat(harness.replayEquals()).isEmpty();
    }

    /** Mutable per-Cell state; only {@code Bump.applyTo} is meant to write it. */
    public static final class Counter {
        public long count;

        public record Bump() implements Change<Counter> {
            @Override
            public void applyTo(Counter state) {
                state.count++;
            }
        }
    }

    /** The defect: writes the field directly, bypassing {@code ctx.change}. */
    @Tasklet.Spec(name = "bypassing_op")
    public static final class BypassingTask implements Tasklet<String, Counter, String> {
        @Override
        public void run(String input, Ctx<Counter, String> ctx) throws TaskletException {
            ctx.state().count += 1; // compiles, runs, never journaled
        }
    }

    /** The correct form, for contrast. */
    @Tasklet.Spec(name = "journaling_op")
    public static final class JournalingTask implements Tasklet<String, Counter, String> {
        @Override
        public void run(String input, Ctx<Counter, String> ctx) throws TaskletException {
            ctx.change(new Counter.Bump());
        }
    }
}
