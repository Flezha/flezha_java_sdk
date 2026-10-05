package com.flezha.sdk.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.runtime.Refused;
import com.flezha.sdk.runtime.core.HostClassDeclaration;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Registration-time refusals of {@link RelayClassRegistry#registerClass}
 * — the authoring validation half of the retired driver suite. Execution
 * semantics (barriers, loops, retries, sagas) are core-owned now; what stays
 * host-side is the refusal of a shape that could not run.
 */
class StepsRegistrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String AUTHORS_REFUSAL = "the author's own refusal";

    @AfterEach
    void reset() {
        RelayClassRegistry.reset();
    }

    @Test
    @DisplayName("A steps class writes channels")
    void aStepsClassWritesChannels() {
        RelayTopology topology = registered(LinearSteps.class);

        assertThat(topology.channels())
                .extracting(ChannelDescriptor::name)
                .as("the graph is the one the class's channels body wired")
                .containsExactly("seed", "echo");
        assertThat(topology.declaration())
                .as("the record the core judged runs in steps")
                .containsEntry(
                        "run", java.util.Map.of("steps", java.util.Map.of("each", "once", "on_failure", "undo_all")));
    }

    @Test
    @DisplayName("a steps relay that declares no tasklet is refused")
    void noTaskletsIsRefused() {
        Refused.by("topology.no_tasklets", () -> RelayClassRegistry.registerClass(NoTaskletsSteps.class));
    }

    @Test
    @DisplayName("The edge's budget bounds the ladder: retryMax(2) reaches the core as the slot's budget,"
            + " retryMax(0) is refused naming topology.retry_max_zero")
    void theEdgesBudgetBoundsTheLadder() throws Exception {
        // The core's stage barrier runs each slot's ladder from this record, whatever the host.
        String factoryId = RelayClassRegistry.registerClass(TwoRetryMaxSteps.class);
        JsonNode seed = JSON.readTree(HostClassDeclaration.forFactory(factoryId))
                .get("channels")
                .get(0);
        assertThat(seed.get("name").asText()).isEqualTo("seed");
        assertThat(seed.get("retry_max").asInt()).isEqualTo(2);

        assertRefusedAtRegistration(ZeroRetryMaxSteps.class, "topology.retry_max_zero");
    }

    /**
     * Registers {@code stepsClass} the way {@code registerFactory} does and asserts the core
     * refused it by {@code code}, leaving nothing registered; every code it listed, for further checks.
     */
    private static List<String> assertRefusedAtRegistration(Class<? extends Relay> stepsClass, String code) {
        RelayClassRegistry.reset();
        FlezhaException refused = Refused.by(code, () -> RelayClassRegistry.registerClass(stepsClass));
        assertThat(RelayClassRegistry.factoryIds()).isEmpty();
        return refused.issueCodes();
    }

    /** Registers {@code stepsClass} and answers the topology it froze. */
    private static RelayTopology registered(Class<? extends Relay> stepsClass) {
        return RelayClassRegistry.getTopology(RelayClassRegistry.registerClass(stepsClass));
    }

    @Test
    @DisplayName("an entry-channel edge transform is accepted: every message names its channel")
    void entryEdgeClosureIsAccepted() {
        ChannelDescriptor entry = registered(EntryClosureSteps.class).channels().stream()
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
    @DisplayName("a keyBy edge into a steps tasklet registers it in dynamic mode")
    void aKeyedEdgeIntoABoundedTaskletRegisters() throws Exception {
        String factoryId = RelayClassRegistry.registerClass(KeyedSteps.class);
        JsonNode tasklets =
                JSON.readTree(HostClassDeclaration.forFactory(factoryId)).get("tasklets");

        assertThat(tasklets.get(1).get("name").asText()).isEqualTo("sink");
        assertThat(tasklets.get(1).get("cell_mode").asText())
                .as("a tasklet a keyed edge reaches is dynamic, as a flow relay's is")
                .isEqualTo("dynamic");
    }

    @Test
    @DisplayName("a cycle is refused — a steps topology is a DAG")
    void cycleIsRefused() {
        Refused.by("topology.cycle", () -> RelayClassRegistry.registerClass(CyclicSteps.class));
    }

    @Test
    @DisplayName("a steps class that never ends is accepted")
    void aStepsClassThatNeverEndsIsAccepted() {
        assertThat(RelayClassRegistry.registerClass(NeverEndingSteps.class)).isEqualTo("vb_never_ending");
    }

    @Test
    @DisplayName("a constructor that throws is reported as the author's error, not as a missing constructor")
    void throwingConstructorIsReportedAsTheAuthorsError() {
        Throwable thrown = catchThrowable(() -> RelayClassRegistry.registerClass(ThrowingConstructorSteps.class));

        assertThat(thrown).hasMessageNotContaining("no-arg constructor");
        assertThat(Arrays.asList(thrown, thrown.getCause()))
                .as("the author's exception is the one thrown or its direct cause")
                .anySatisfy(t ->
                        assertThat(t).isInstanceOf(IllegalStateException.class).hasMessage(AUTHORS_REFUSAL));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    @Tasklet.Spec(name = "echo")
    public static final class EchoTask implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) throws com.flezha.sdk.tasklet.TaskletException {
            ctx.emit(input);
        }
    }

    @Tasklet.Spec(name = "sink")
    public static final class SinkTask implements Tasklet<String, Void, Void> {

        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}
    }

    public static final class LinearSteps implements Relay {
        @Override
        public String factoryId() {
            return "vb_linear";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").into(EchoTask.class).into(SinkTask.class);
        }
    }

    public static final class KeyedSteps implements Relay {
        @Override
        public String factoryId() {
            return "vb_keyed";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").into(EchoTask.class).keyBy(s -> s).into(SinkTask.class);
        }
    }

    public static final class NoTaskletsSteps implements Relay {
        @Override
        public String factoryId() {
            return "vb_no_tasklets";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    /** A steps relay whose entry edge allows no attempt at all. */
    public static final class TwoRetryMaxSteps implements Relay {
        @Override
        public String factoryId() {
            return "vb_two_retry_max";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").retryMax(2).into(SinkTask.class);
        }
    }

    public static final class ZeroRetryMaxSteps implements Relay {
        @Override
        public String factoryId() {
            return "vb_zero_retry_max";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").retryMax(0).into(SinkTask.class);
        }
    }

    public static final class EntryClosureSteps implements Relay {
        @Override
        public String factoryId() {
            return "vb_entry_closure";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").map(String.class, String::toUpperCase).into(SinkTask.class);
        }
    }

    public static final class CyclicSteps implements Relay {
        @Override
        public String factoryId() {
            return "vb_cycle";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public Durable durable() {
            return new Durable.Memory();
        }

        @Override
        public void channels(ChannelBuilder c) {
            var a = c.input(String.class, "seed").into(EchoTask.class);
            var b = a.into(EchoTask.class, "echo_b");
            b.into(EchoTask.class);
        }
    }

    public static final class NeverEndingSteps implements Relay {
        @Override
        public String factoryId() {
            return "vb_never_ending";
        }

        @Override
        public Run run() {
            return Run.steps(Each.PER_INPUT);
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "seed").into(EchoTask.class).into(SinkTask.class);
        }
    }

    public static final class ThrowingConstructorSteps implements Relay {
        public ThrowingConstructorSteps() {
            throw new IllegalStateException(AUTHORS_REFUSAL);
        }

        @Override
        public String factoryId() {
            return "vb_throwing_constructor";
        }

        @Override
        public Run run() {
            return Run.steps();
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }
}
