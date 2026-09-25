package com.flezha.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.testing.TaskletTestHarness;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Asserts the {@code @UnboundedOperator} / {@code @BoundedOperator} / {@code @Tasklet} /
 * {@code @State}-generated operators register and process input identically to the hand-written
 * equivalents, and that the 2-tasklet reference operator matches the cross-SDK authoring contract
 * (see {@code docs/sdk/authoring_contract.md} §6).
 */
class SugarTwinTest {

    @BeforeEach
    void reset() {
        UnboundedOperatorClassRegistry.reset();
        com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry.reset();
    }

    @Test
    void sugarDoNothingEmitsBytesLikeHandWritten() {
        var sugar = TaskletTestHarness.of(new SugarDoNothingTasklet());
        sugar.applyInputs(List.of("alpha", "beta"));
        var emits = sugar.recordedEmits();
        assertThat(emits).hasSize(2);
        assertThat((byte[]) emits.get(0)).isEqualTo("alpha".getBytes(StandardCharsets.UTF_8));
        assertThat((byte[]) emits.get(1)).isEqualTo("beta".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sugarStoreJournalsAndEmits() {
        var h = TaskletTestHarness.of(new SugarStoreTasklet());
        h.applyInputs(List.of("one", "two"));
        h.expectEmits(String.class, List.of("one", "two"));
        assertThat(h.state().text_inputs).containsExactly("one", "two");
        assertThat(h.replayEquals()).isEmpty();
    }

    @Test
    void sugarEntryCellsAndJournals() {
        var tasklet = new SugarEntryTasklet();
        assertThat(tasklet.cellsFor("apple")).containsExactly("a");
        assertThat(tasklet.cellsFor("zebra")).containsExactly("d");
        var topology = UnboundedOperatorTopologyBuilder.build(new SugarStaticCellUnboundedOperator());
        assertThat(topology.tasklet(topology.taskletNameToId().get(tasklet.name()))
                        .cellsForOverridden())
                .as("the generated class writes cellsFor, which is what declares it")
                .isTrue();
        assertThat(tasklet.cellKeys()).containsExactly("a", "b", "c", "d");

        var h = TaskletTestHarness.of(tasklet);
        h.applyInputs(List.of("apple", "avocado"));
        h.expectEmits(String.class, List.of("apple", "avocado"));
        assertThat(h.state().seen).containsExactly("apple", "avocado");
        assertThat(h.replayEquals()).isEmpty();
    }

    /**
     * fails when: a short form registers itself again, or the author has to name a generated class.
     * Nothing is collected and nothing is harvested: the application hands the runner the classes it
     * wrote.
     */
    @Test
    void shortFormsRegisterOnlyWhenTheAuthorRegistersThem() {
        var runner = com.flezha.sdk.Flezha.createRunner("sugar_twin_ns");
        assertThat(UnboundedOperatorClassRegistry.factoryIds()).isEmpty();

        runner.registerFactory(SugarDoNothing.class)
                .registerFactory(SugarStore.class)
                .registerFactory(SugarNamedInput.class)
                .registerFactory(SugarStateOnly.class)
                .registerFactory(SugarStaticCellUnboundedOperator.class);

        assertThat(UnboundedOperatorClassRegistry.factoryIds())
                .contains(
                        "sugar_do_nothing",
                        "sugar_store_input",
                        "sugar_named_input",
                        "sugar_state_only",
                        "sugar_static_cell");

        runner.unregisterFactory(SugarStateOnly.class);
        assertThat(UnboundedOperatorClassRegistry.factoryIds()).doesNotContain("sugar_state_only");
    }

    /** fails when: the bounded short form stops declaring a bounded operator. */
    @Test
    void theBoundedShortFormRegistersAsABounded() {
        com.flezha.sdk.Flezha.createRunner("sugar_twin_bounded_ns").registerFactory(SugarBounded.class);
        assertThat(com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry.factoryIds())
                .contains("sugar_bounded");
    }

    /**
     * fails when: a short form with no sink stops declaring its entry channel and tasklet alone, or
     * stops keeping what it is sent in the state its tasklet, named after the factory id, owns.
     */
    @Test
    void aShortFormWithNoSinkDeclaresNoPluginChannelAndKeepsState() {
        var topology = UnboundedOperatorTopologyBuilder.build(new SugarStateOnlyUnboundedOperator());
        assertThat(topology.channels()).hasSize(1);
        assertThat(topology.channels().get(0).name()).isEqualTo("ticks");
        assertThat(topology.channels().get(0).pluginSink()).isEmpty();

        var tasklet = new SugarStateOnlyTasklet();
        assertThat(tasklet.name()).isEqualTo("sugar_state_only");
        var h = TaskletTestHarness.of(tasklet);
        h.applyInputs(List.of("one", "two"));
        assertThat(h.recordedEmits()).isEmpty();
        assertThat(h.state().seen).containsExactly("one", "two");
        assertThat(h.replayEquals()).isEmpty();
    }

    /** fails when: the short form stops honouring the entry channel name the author wrote. */
    @Test
    void theGeneratedShorthandNamesItsEntryChannel() {
        var topology = UnboundedOperatorTopologyBuilder.build(new SugarNamedInputUnboundedOperator());
        assertThat(topology.channels().get(0).name()).isEqualTo("prices");
    }

    /** fails when: a written id stops naming the tasklet, so its state is saved elsewhere. */
    @Test
    void aWrittenIdNamesTheTasklet() {
        assertThat(new SugarStoreTasklet().name()).isEqualTo("sugar_store_input");
    }

    /**
     * fails when: the two kinds stop sharing one factory-id namespace, so a bounded operator could be
     * placed under an id an unbounded operator already answers. The refusal is the real registries' — this is
     * the module where both are on the classpath.
     */
    @Test
    void aBoundedUnderAnUnboundedFactoryIdIsRefused() {
        var runner = com.flezha.sdk.Flezha.createRunner("sugar_twin_dup_ns").registerFactory(ShorthandDupUnbounded.class);

        assertThatThrownBy(() -> runner.registerFactory(ShorthandDupBounded.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("DuplicateFactoryId")
                .hasMessageContaining("plain");

        assertThat(com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry.factoryIds())
                .doesNotContain("plain");
    }

    /**
     * fails when: the generated shorthand stops naming its entry channel
     * {@code input} — the address §6's conformance table and every operator
     * in the tree ships under.
     */
    @Test
    void theGeneratedShorthandKeepsTheDefaultEntryChannelName() {
        var topology = UnboundedOperatorTopologyBuilder.build(new SugarDoNothingUnboundedOperator());
        assertThat(topology.channels().get(0).name()).isEqualTo("input");
    }

    /**
     * Cross-SDK conformance: the reference operator's associated-type mapping
     * matches the contract table (and the Rust expansion of the same operator).
     */
    @Test
    void referenceOperatorMatchesContract() {
        var entry = new SugarEntryTasklet();
        assertThat(entry.inputType()).isEqualTo(String.class);
        assertThat(entry.outputType()).isEqualTo(String.class);
        assertThat(entry.changeType()).isEqualTo(SugarEntryStateChange.class);
        assertThat(entry.clockType()).isEqualTo(WallClock.class);
        assertThat(entry.configType()).isEqualTo(Void.class);
        assertThat(entry.cellKeys()).containsExactly("a", "b", "c", "d");

        var down = new SugarDownstreamTasklet();
        assertThat(down.inputType()).isEqualTo(String.class);
        assertThat(down.outputType()).isEqualTo(byte[].class);
        assertThat(down.changeType()).isEqualTo(NoChange.class);
        assertThat(down.cellKeys()).isEmpty();
    }

    /**
     * Canonical keyed-collection reference tasklet (`expressive-state-journaling`):
     * the `@Journal(MAP_DEQUE)` + `Optional<Output>` sugar processes input
     * identically to the hand-written twin and survives `replayEquals`.
     */
    @Test
    void rollingWindowMatchesHandWrittenTwin() {
        List<WindowInput> inputs = List.of(
                new WindowInput("a", 1.0),
                new WindowInput("a", 2.0),
                new WindowInput("a", 3.0),
                new WindowInput("a", 4.0),
                new WindowInput("b", 10.0),
                new WindowInput("b", 20.0),
                new WindowInput("b", 30.0));

        var sugar = TaskletTestHarness.of(new RollingWindowTasklet());
        sugar.applyInputs(inputs);

        var twin = TaskletTestHarness.of(new RollingWindowTwin());
        twin.applyInputs(inputs);

        // Same emits (warmup gate via Optional<Output>).
        assertThat(sugar.recordedEmits()).isEqualTo(twin.recordedEmits());
        assertThat(sugar.recordedEmits()).containsExactly(2.0, 3.0, 20.0);

        // Same per-key window contents (ArrayDeque has identity equals, so
        // compare element-wise).
        assertThat(sugar.state().window.keySet()).isEqualTo(twin.state().window.keySet());
        for (String key : sugar.state().window.keySet()) {
            assertThat(new ArrayList<>(sugar.state().window.get(key)))
                    .isEqualTo(new ArrayList<>(twin.state().window.get(key)));
        }

        // The generated journaling replays deterministically.
        assertThat(sugar.replayEquals()).isEmpty();
    }

    /** Exercises the generated MAP_SET / MAP_VEC mutators + a composed flat field. */
    @Test
    void keyedCollectionsModesJournalAndReplay() {
        List<WindowInput> inputs = List.of(
                new WindowInput("x", 1.0),
                new WindowInput("x", 2.0),
                new WindowInput("x", 3.0),
                new WindowInput("x", 4.0),
                new WindowInput("y", 9.0));

        var h = TaskletTestHarness.of(new KeyedCollectionsTasklet());
        h.applyInputs(inputs);

        assertThat(h.state().last.get("x")).isEqualTo(4.0);
        assertThat(h.state().last.get("y")).isEqualTo(9.0);
        assertThat(h.state().closes.get("x")).containsExactly(2.0, 3.0, 4.0);
        assertThat(h.state().closes.get("y")).containsExactly(9.0);
        assertThat(h.state().ticks).isEqualTo(5);

        assertThat(h.replayEquals()).isEmpty();
    }

    /**
     * {@code @InitState} maps the static cell key to per-cell state
     * the default {@code new State()} cannot derive (window length from the
     * cell key). Each cell then behaves per its own window, and replay
     * stays deterministic against the captured initial snapshot.
     */
    @Test
    void taskletInitMapsCellKeyToState() {
        var tasklet = new WindowedTasklet();
        assertThat(tasklet.initState(Optional.of("win_2"), Optional.empty()).window).isEqualTo(2);
        assertThat(tasklet.initState(Optional.of("win_3"), Optional.empty()).window).isEqualTo(3);
        // Cold-start / harness default (key absent) falls through to the else arm.
        assertThat(tasklet.initState(Optional.empty(), Optional.empty()).window).isEqualTo(3);

        List<WindowInput> inputs =
                List.of(new WindowInput("a", 2.0), new WindowInput("a", 4.0), new WindowInput("a", 6.0));

        var w2 = TaskletTestHarness.of(tasklet)
                .withInitialState(tasklet.initState(Optional.of("win_2"), Optional.empty()));
        w2.applyInputs(inputs);
        w2.expectEmits(Double.class, List.of(3.0, 5.0)); // means of [2,4] then [4,6]
        assertThat(w2.replayEquals()).isEmpty();

        var w3 = TaskletTestHarness.of(tasklet)
                .withInitialState(tasklet.initState(Optional.of("win_3"), Optional.empty()));
        w3.applyInputs(inputs);
        w3.expectEmits(Double.class, List.of(4.0)); // single mean of [2,4,6]
        assertThat(w3.replayEquals()).isEmpty();
    }
}
