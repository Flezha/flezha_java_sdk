package com.flezha.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.runtime.operator.TaskletModel;
import com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry;
import com.flezha.sdk.runtime.operator.UnboundedOperatorTopology;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder;
import com.flezha.sdk.runtime.state.ClockKind;
import com.flezha.sdk.testing.TaskletTestHarness;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Asserts the {@code @UnboundedOperator} / {@code @BoundedOperator} short forms register under their
 * ids and process input as the equivalent hand-written operators do, and that the 2-tasklet
 * reference operator matches the cross-SDK authoring contract (see
 * {@code docs/sdk/authoring_contract.md} §6).
 */
class SugarTwinTest {

    @BeforeEach
    void reset() {
        UnboundedOperatorClassRegistry.reset();
        com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry.reset();
    }

    /** The topology the short form on {@code cls} declares, as registration builds it. */
    private static UnboundedOperatorTopology shortForm(Class<?> cls) {
        return UnboundedOperatorTopologyBuilder.build(UnboundedOperatorClassRegistry.probe(cls, UnboundedOperator.class));
    }

    @Test
    void sugarDoNothingEmitsBytesLikeHandWritten() {
        List<String> inputs = List.of("alpha", "beta");
        var sugar = TaskletTestHarness.of(new SugarDoNothing());
        sugar.applyInputs(inputs);
        var hand = TaskletTestHarness.of(new DoNothingTasklet());
        hand.applyInputs(inputs);

        var emits = sugar.recordedEmits();
        assertThat(emits).hasSize(2);
        assertThat((byte[]) emits.get(0)).isEqualTo("alpha".getBytes(StandardCharsets.UTF_8));
        assertThat((byte[]) emits.get(1)).isEqualTo("beta".getBytes(StandardCharsets.UTF_8));
        assertThat(hand.recordedEmits()).hasSize(2);
        assertThat((byte[]) hand.recordedEmits().get(0)).isEqualTo((byte[]) emits.get(0));
        assertThat((byte[]) hand.recordedEmits().get(1)).isEqualTo((byte[]) emits.get(1));
    }

    @Test
    void sugarStoreJournalsAndEmitsLikeHandWritten() {
        List<String> inputs = List.of("one", "two");
        var h = TaskletTestHarness.of(new SugarStore());
        h.applyInputs(inputs);
        h.expectEmits(String.class, List.of("one", "two"));
        assertThat(h.state().text_inputs).containsExactly("one", "two");
        assertThat(h.replayEquals()).isEmpty();

        var hand = TaskletTestHarness.of(new StoreInputTasklet());
        hand.applyInputs(inputs);
        assertThat(hand.recordedEmits()).isEqualTo(h.recordedEmits());
        assertThat(hand.state().text_inputs()).isEqualTo(h.state().text_inputs);
    }

    @Test
    void sugarEntryCellsAndJournals() {
        var tasklet = new SugarEntry();
        assertThat(tasklet.cellsFor("apple")).containsExactly("a");
        assertThat(tasklet.cellsFor("zebra")).containsExactly("d");
        var topology = UnboundedOperatorTopologyBuilder.build(new SugarStaticCellUnboundedOperator());
        var entry = topology.tasklet(topology.taskletNameToId().get("macro_static_entry_tasklet"));
        assertThat(entry.cellsForOverridden())
                .as("the class writes cellsFor, which is what declares it")
                .isTrue();
        assertThat(entry.cellKeys()).containsExactly("a", "b", "c", "d");

        var h = TaskletTestHarness.of(tasklet);
        h.applyInputs(List.of("apple", "avocado"));
        h.expectEmits(String.class, List.of("apple", "avocado"));
        assertThat(h.state().seen).containsExactly("apple", "avocado");
        assertThat(h.replayEquals()).isEmpty();
    }

    /**
     * fails when: a short form registers itself. Nothing is collected and nothing is harvested: the
     * application hands the runner the classes it wrote.
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
        var topology = shortForm(SugarStateOnly.class);
        assertThat(topology.channels()).hasSize(1);
        assertThat(topology.channels().get(0).name()).isEqualTo("ticks");
        assertThat(topology.channels().get(0).pluginSink()).isEmpty();
        assertThat(topology.taskletNameToId()).containsOnlyKeys("sugar_state_only");

        var h = TaskletTestHarness.of(new SugarStateOnly());
        h.applyInputs(List.of("one", "two"));
        assertThat(h.recordedEmits()).isEmpty();
        assertThat(h.state().seen).containsExactly("one", "two");
        assertThat(h.replayEquals()).isEmpty();
    }

    /** fails when: the short form stops honouring the entry channel name the author wrote. */
    @Test
    void theShorthandNamesItsEntryChannel() {
        assertThat(shortForm(SugarNamedInput.class).channels().get(0).name()).isEqualTo("prices");
    }

    /** fails when: a written id stops naming the tasklet, so its state is saved elsewhere. */
    @Test
    void aWrittenIdNamesTheTasklet() {
        assertThat(TaskletModel.read(SugarStore.class, null).name()).isEqualTo("sugar_store_input");
        assertThat(shortForm(SugarStore.class).taskletNameToId()).containsOnlyKeys("sugar_store_input");
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
     * fails when: the shorthand stops naming its entry channel {@code input} — the address §6's
     * conformance table and every operator in the tree ships under.
     */
    @Test
    void theShorthandKeepsTheDefaultEntryChannelName() {
        assertThat(shortForm(SugarDoNothing.class).channels().get(0).name()).isEqualTo("input");
    }

    /**
     * Cross-SDK conformance: the reference operator's type mapping matches the contract table (and
     * the Rust expansion of the same operator).
     */
    @Test
    void referenceOperatorMatchesContract() {
        var entry = TaskletModel.read(SugarEntry.class, null);
        assertThat(entry.inputType()).isEqualTo(String.class);
        assertThat(entry.outputType()).isEqualTo(String.class);
        assertThat(entry.stateType()).isEqualTo(SugarEntryState.class);
        assertThat(entry.clock()).isEqualTo(ClockKind.WALL);
        assertThat(entry.cellKeys()).containsExactly("a", "b", "c", "d");

        var down = TaskletModel.read(SugarDownstream.class, null);
        assertThat(down.inputType()).isEqualTo(String.class);
        assertThat(down.outputType()).isEqualTo(byte[].class);
        assertThat(down.stateless()).isTrue();
        assertThat(down.cellKeys()).isEmpty();
    }

    /**
     * Canonical keyed-collection reference tasklet: the per-key window emits only once full and
     * survives {@code replayEquals}.
     */
    @Test
    void rollingWindowGatesWarmupAndReplays() {
        List<WindowInput> inputs = List.of(
                new WindowInput("a", 1.0),
                new WindowInput("a", 2.0),
                new WindowInput("a", 3.0),
                new WindowInput("a", 4.0),
                new WindowInput("b", 10.0),
                new WindowInput("b", 20.0),
                new WindowInput("b", 30.0));

        var h = TaskletTestHarness.of(new RollingWindow());
        h.applyInputs(inputs);

        assertThat(h.recordedEmits()).containsExactly(2.0, 3.0, 20.0);
        assertThat(h.state().window.keySet()).containsExactlyInAnyOrder("a", "b");
        assertThat(new ArrayList<>(h.state().window.get("a"))).containsExactly(2.0, 3.0, 4.0);
        assertThat(new ArrayList<>(h.state().window.get("b"))).containsExactly(10.0, 20.0, 30.0);
        assertThat(h.replayEquals()).isEmpty();
    }

    /** Exercises the per-key set, append and drain changes + a composed flat field. */
    @Test
    void keyedCollectionsModesJournalAndReplay() {
        List<WindowInput> inputs = List.of(
                new WindowInput("x", 1.0),
                new WindowInput("x", 2.0),
                new WindowInput("x", 3.0),
                new WindowInput("x", 4.0),
                new WindowInput("y", 9.0));

        var h = TaskletTestHarness.of(new KeyedCollections());
        h.applyInputs(inputs);

        assertThat(h.state().last.get("x")).isEqualTo(4.0);
        assertThat(h.state().last.get("y")).isEqualTo(9.0);
        assertThat(h.state().closes.get("x")).containsExactly(2.0, 3.0, 4.0);
        assertThat(h.state().closes.get("y")).containsExactly(9.0);
        assertThat(h.state().ticks).isEqualTo(5);

        assertThat(h.replayEquals()).isEmpty();
    }

    /**
     * {@code initState} maps the static cell key to per-cell state the no-arg constructor cannot
     * derive (window length from the cell key). Each cell then behaves per its own window, and
     * replay stays deterministic against the captured initial snapshot.
     */
    @Test
    void taskletInitMapsCellKeyToState() {
        var tasklet = new Windowed();
        assertThat(tasklet.initState(Optional.of("win_2")).window).isEqualTo(2);
        assertThat(tasklet.initState(Optional.of("win_3")).window).isEqualTo(3);
        // Cold-start / harness default (key absent) falls through to the else arm.
        assertThat(tasklet.initState(Optional.empty()).window).isEqualTo(3);

        List<WindowInput> inputs =
                List.of(new WindowInput("a", 2.0), new WindowInput("a", 4.0), new WindowInput("a", 6.0));

        var w2 = TaskletTestHarness.of(tasklet).withInitialState(tasklet.initState(Optional.of("win_2")));
        w2.applyInputs(inputs);
        w2.expectEmits(Double.class, List.of(3.0, 5.0)); // means of [2,4] then [4,6]
        assertThat(w2.replayEquals()).isEmpty();

        var w3 = TaskletTestHarness.of(tasklet).withInitialState(tasklet.initState(Optional.of("win_3")));
        w3.applyInputs(inputs);
        w3.expectEmits(Double.class, List.of(4.0)); // single mean of [2,4,6]
        assertThat(w3.replayEquals()).isEmpty();
    }
}
