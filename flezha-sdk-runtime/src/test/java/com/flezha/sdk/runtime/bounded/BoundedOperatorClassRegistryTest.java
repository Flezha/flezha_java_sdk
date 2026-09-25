package com.flezha.sdk.runtime.bounded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.operator.BoundedOperator;
import com.flezha.sdk.operator.BoundedOperatorConfigurationException;
import com.flezha.sdk.runtime.core.HostClassDeclaration;
import com.flezha.sdk.runtime.operator.ChannelDescriptor;
import com.flezha.sdk.runtime.operator.StatelessTestTasklet;
import com.flezha.sdk.runtime.operator.UnboundedOperatorTopology;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.TaskletCtx;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Registration-time refusals of {@link BoundedOperatorClassRegistry#registerClass}
 * — the authoring validation half of the retired driver suite. Execution
 * semantics (barriers, loops, retries, sagas) are core-owned now; what stays
 * host-side is the refusal of a shape that could not run.
 */
class BoundedOperatorClassRegistryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String AUTHORS_REFUSAL = "the author's own refusal";

    @AfterEach
    void reset() {
        BoundedOperatorClassRegistry.reset();
    }

    @Test
    @DisplayName("A bounded class writes channels")
    void aBoundedClassWritesChannels() {
        UnboundedOperatorTopology topology = registered(LinearBounded.class);

        assertThat(topology.channels())
                .extracting(ChannelDescriptor::name)
                .as("the graph is the one the class's channels body wired")
                .containsExactly("seed", "echo");
        assertThat(topology.declaration())
                .as("the record the core judged is a bounded operator's")
                .containsKey("bounded");
    }

    @Test
    @DisplayName("a bounded operator that declares no tasklet is refused")
    void noTaskletsIsRefused() {
        assertThatThrownBy(() -> BoundedOperatorClassRegistry.registerClass(NoTaskletsBounded.class))
                .isInstanceOf(BoundedOperatorConfigurationException.class)
                .hasMessageContaining("vb_no_tasklets")
                .hasMessageContaining("at least one tasklet");
    }

    @Test
    @DisplayName("The tier on a bounded class is refused by the core's name")
    void theTierOnABoundedClassIsRefusedByTheCoresName() {
        assertRefusedAtRegistration(TierBounded.class, "EffectivelyOnceOnBounded");
    }

    @Test
    @DisplayName("A checkpoint setting on a bounded class is refused by its own name")
    void aCheckpointSettingOnABoundedClassIsRefusedByItsOwnName() {
        assertThat(assertRefusedAtRegistration(CheckpointBounded.class, "CheckpointOnBounded"))
                .as("the reason is the kind, not the tier the unbounded rule would ask for")
                .doesNotContain("CheckpointAckWithoutTier");
    }

    @Test
    @DisplayName("a correction window on a bounded class is refused by its own name")
    void aCorrectionWindowOnABoundedClassIsRefusedByItsOwnName() {
        assertRefusedAtRegistration(CorrectionBounded.class, "CorrectionWindowOnBounded");
    }

    @Test
    @DisplayName("The edge's budget bounds the ladder: retryMax(2) reaches the core as the slot's budget,"
            + " retryMax(0) is refused naming RetryMaxZero")
    void theEdgesBudgetBoundsTheLadder() throws Exception {
        // The core's stage barrier runs each slot's ladder from this record, whatever the host.
        String factoryId = BoundedOperatorClassRegistry.registerClass(TwoRetryMaxBounded.class);
        JsonNode seed = JSON.readTree(HostClassDeclaration.forFactory(factoryId))
                .get("channels")
                .get(0);
        assertThat(seed.get("name").asText()).isEqualTo("seed");
        assertThat(seed.get("retry_max").asInt()).isEqualTo(2);

        assertRefusedAtRegistration(ZeroRetryMaxBounded.class, "RetryMaxZero");
    }

    /**
     * Registers {@code boundedClass} the way {@code registerFactory} does and asserts the core
     * refused it by {@code name}, leaving nothing registered; the message, for further checks.
     */
    private static String assertRefusedAtRegistration(Class<? extends BoundedOperator> boundedClass, String name) {
        BoundedOperatorClassRegistry.reset();
        Throwable thrown = catchThrowable(() -> BoundedOperatorClassRegistry.registerClass(boundedClass));

        assertThat(thrown).isInstanceOf(BoundedOperatorConfigurationException.class);
        assertThat(thrown.getMessage()).contains(name);
        assertThat(BoundedOperatorClassRegistry.factoryIds()).isEmpty();
        return thrown.getMessage();
    }

    /** Registers {@code boundedClass} and answers the topology it froze. */
    private static UnboundedOperatorTopology registered(Class<? extends BoundedOperator> boundedClass) {
        return BoundedOperatorClassRegistry.getTopology(BoundedOperatorClassRegistry.registerClass(boundedClass));
    }

    @Test
    @DisplayName("an entry-channel edge transform is accepted: every message names its channel")
    void entryEdgeClosureIsAccepted() {
        ChannelDescriptor entry = registered(EntryClosureBounded.class).channels().stream()
                .filter(c -> c.kind() == ChannelKind.ENTRY)
                .findFirst()
                .orElseThrow();
        assertThat(entry.name()).isEqualTo("seed");
        assertThat(entry.consumers()).hasSize(1);
        assertThat(entry.consumers().get(0).transform())
                .as("the transform declared on the entry edge is kept, not refused")
                .isPresent();
    }

    @Test
    @DisplayName("a keyBy edge into a bounded tasklet registers it in dynamic mode")
    void aKeyedEdgeIntoABoundedTaskletRegisters() throws Exception {
        String factoryId = BoundedOperatorClassRegistry.registerClass(KeyedBounded.class);
        JsonNode tasklets =
                JSON.readTree(HostClassDeclaration.forFactory(factoryId)).get("tasklets");

        assertThat(tasklets.get(1).get("name").asText()).isEqualTo("sink");
        assertThat(tasklets.get(1).get("cell_mode").asText())
                .as("a tasklet a keyed edge reaches is dynamic, as an unbounded operator's is")
                .isEqualTo("dynamic");
    }

    @Test
    @DisplayName("a cycle is refused — a bounded topology is a DAG")
    void cycleIsRefused() {
        assertThatThrownBy(() -> BoundedOperatorClassRegistry.registerClass(CyclicBounded.class))
                .isInstanceOf(BoundedOperatorConfigurationException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("an operator whose only exit is an external stop is refused")
    void externalStopOnlyIsRefused() {
        assertThatThrownBy(() -> BoundedOperatorClassRegistry.registerClass(NeverEndingBounded.class))
                .isInstanceOf(BoundedOperatorConfigurationException.class)
                .hasMessageContaining("terminable");
    }

    @Test
    @DisplayName("a constructor that throws is reported as the author's error, not as a missing constructor")
    void throwingConstructorIsReportedAsTheAuthorsError() {
        Throwable thrown =
                catchThrowable(() -> BoundedOperatorClassRegistry.registerClass(ThrowingConstructorBounded.class));

        assertThat(thrown).hasMessageNotContaining("no-arg constructor");
        assertThat(Arrays.asList(thrown, thrown.getCause()))
                .as("the author's exception is the one thrown or its direct cause")
                .anySatisfy(t ->
                        assertThat(t).isInstanceOf(IllegalStateException.class).hasMessage(AUTHORS_REFUSAL));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    public static final class EchoTask extends StatelessTestTasklet<String, Object, String> {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx)
                throws com.flezha.sdk.tasklet.TaskletException {
            ctx.emit(input);
        }
    }

    public static final class SinkTask extends StatelessTestTasklet<String, Object, Void> {
        @Override
        public String name() {
            return "sink";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class LinearBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "vb_linear";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").into(EchoTask.class).into(SinkTask.class);
        }
    }

    public static final class KeyedBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "vb_keyed";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").into(EchoTask.class).keyBy(s -> s).into(SinkTask.class);
        }
    }

    public static final class NoTaskletsBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "vb_no_tasklets";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    /** A bounded operator whose builder writes the unbounded tier. */
    public static final class TierBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "vb_tier";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.effectivelyOnce();
            c.input(String.class, "seed").into(SinkTask.class);
        }
    }

    /** A bounded operator whose builder writes a checkpoint interval and nothing else unbounded-only. */
    public static final class CheckpointBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "vb_checkpoint";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.checkpointEvery(100);
            c.input(String.class, "seed").into(SinkTask.class);
        }
    }

    /** A bounded operator whose builder writes a correction window. */
    public static final class CorrectionBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "vb_correction";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.correctionWindow(60_000);
            c.input(String.class, "seed").into(SinkTask.class);
        }
    }

    /** A bounded operator whose entry edge allows no attempt at all. */
    public static final class TwoRetryMaxBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "vb_two_retry_max";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").retryMax(2).into(SinkTask.class);
        }
    }

    public static final class ZeroRetryMaxBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "vb_zero_retry_max";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").retryMax(0).into(SinkTask.class);
        }
    }

    public static final class EntryClosureBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "vb_entry_closure";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").map(String.class, String::toUpperCase).into(SinkTask.class);
        }
    }

    public static final class CyclicBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "vb_cycle";
        }

        @Override
        public boolean persistenceEnabled() {
            return false;
        }

        @Override
        public void channels(ChannelBuilder c) {
            var a = c.input(String.class, "seed").into(EchoTask.class);
            var b = a.into(EchoTask.class, "echo_b");
            b.into(EchoTask.class);
        }
    }

    public static final class NeverEndingBounded implements BoundedOperator {
        @Override
        public String factoryId() {
            return "vb_never_ending";
        }

        @Override
        public boolean selfTerminating() {
            return false;
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").into(EchoTask.class).into(SinkTask.class);
        }
    }

    public static final class ThrowingConstructorBounded implements BoundedOperator {
        public ThrowingConstructorBounded() {
            throw new IllegalStateException(AUTHORS_REFUSAL);
        }

        @Override
        public String factoryId() {
            return "vb_throwing_constructor";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }
}
