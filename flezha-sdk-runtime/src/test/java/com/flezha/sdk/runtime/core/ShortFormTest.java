package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.authoring.Operator;
import com.flezha.sdk.authoring.Operator.Checkpoint;
import com.flezha.sdk.authoring.Operator.Durable;
import com.flezha.sdk.authoring.Operator.EffectivelyOnce;
import com.flezha.sdk.authoring.Operator.Flow;
import com.flezha.sdk.authoring.Operator.Level;
import com.flezha.sdk.authoring.Operator.Run;
import com.flezha.sdk.authoring.Operator.Steps;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.operator.Operator.Each;
import com.flezha.sdk.runtime.Refused;
import com.flezha.sdk.runtime.operator.OperatorClassRegistry;
import com.flezha.sdk.runtime.operator.OperatorTopology;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The short form, read at registration from the tasklet class the author wrote. */
class ShortFormTest extends DispatchTestBase {

    @Test
    @DisplayName("An annotated class is a whole flow operator: entry `input`, the tasklet, sink `out`")
    void anAnnotatedClassIsAWholeOperator() {
        assertThat(OperatorClassRegistry.registerClass(Echo.class)).isEqualTo("echo");
        OperatorTopology topology = OperatorClassRegistry.getTopology("echo");

        assertThat(topology.tasklets()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("echo");
            assertThat(t.taskletClass()).as("the class the author wrote").isEqualTo(Echo.class);
        });
        assertThat(topology.channel(topology.lookupChannelId("input")).kind()).isEqualTo(ChannelKind.ENTRY);
        assertThat(topology.channel(topology.lookupChannelId("out")).pluginSink())
                .isPresent();

        ItemEffect effect = soleEffect(work("echo", 0, Optional.empty(), 0, input(0, "hi", 1)));
        assertThat(new String(effect.outputs().get(0).payload(), StandardCharsets.UTF_8))
                .isEqualTo("echo: hi");
    }

    @Test
    @DisplayName("An annotated class is a whole steps operator, run once on its named entry")
    void anAnnotatedClassIsAWholeStepsOperator() {
        OperatorClassRegistry.registerClass(Place.class);
        OperatorTopology topology = OperatorClassRegistry.getTopology("place");

        assertThat(topology.lookupChannelId("order")).isNotNull();
        assertThat(stepsBlock(topology)).containsEntry("each", "once").containsEntry("on_failure", "undo_all");

        ItemEffect effect = soleEffect(work("place", 0, Optional.empty(), 0, input(0, "o-1", 1)));
        assertThat(effect.outputs()).singleElement().satisfies(out -> {
            assertThat(out.target()).isEqualTo(new OutputTarget.Plugin("placed"));
            assertThat(new String(out.payload(), StandardCharsets.UTF_8)).isEqualTo("placed o-1");
        });
    }

    @Test
    @DisplayName("A short-form steps operator declares its loop shape")
    void aShortFormOperatorDeclaresItsEach() {
        OperatorClassRegistry.registerClass(Chat.class);
        assertThat(stepsBlock(OperatorClassRegistry.getTopology("chat"))).containsEntry("each", "per_input");
    }

    @Test
    @DisplayName("A short form with no sink is declared: its entry channel into the tasklet and no plugin channel")
    void aShortFormWithNoSinkIsDeclared() {
        OperatorClassRegistry.registerClass(Counter.class);
        OperatorTopology topology = OperatorClassRegistry.getTopology("counter");

        assertThat(topology.channels()).singleElement().satisfies(c -> assertThat(c.name())
                .isEqualTo("ticks"));
        assertThat(topology.channels()).noneMatch(c -> c.pluginSink().isPresent());
    }

    @Test
    @DisplayName("The entry channel keeps its default name; the annotation names the entry channel")
    void theEntryChannelIsNamedByTheAnnotationOrInput() {
        OperatorClassRegistry.registerClass(Echo.class);
        OperatorClassRegistry.registerClass(Prices.class);

        assertThat(OperatorClassRegistry.getTopology("echo").lookupChannelId("input"))
                .isNotNull();
        assertThat(OperatorClassRegistry.getTopology("prices").lookupChannelId("prices"))
                .isNotNull();
    }

    @Test
    @DisplayName("Nothing registers itself: an annotated class never registered declares no operator")
    void nothingRegistersItself() {
        assertThat(OperatorClassRegistry.factoryIds()).doesNotContain("never_registered");
    }

    @Test
    @DisplayName("A short form takes settings from @Tasklet.Spec")
    void aShortFormTakesSettingsFromTaskletSpec() {
        OperatorClassRegistry.registerClass(Counter.class);
        assertThat(OperatorClassRegistry.getTopology("counter").tasklets())
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.name()).isEqualTo("counter");
                    assertThat(t.maxCells()).hasValue(10L);
                });
    }

    @Test
    @DisplayName("An annotation with no id is refused naming the required id")
    void anAnnotationWithNoIdIsRefused() {
        FlezhaException refused =
                Refused.by("topology.missing_factory_id", () -> OperatorClassRegistry.registerClass(Upcase.class));
        assertThat(refused.details()).containsEntry("operator", Upcase.class.getName());
    }

    @Test
    @DisplayName(
            "An annotated class implementing an operator interface is refused as topology.short_form_with_topology")
    void anAnnotatedClassImplementingAnOperatorInterfaceIsRefused() {
        Refused.by("topology.short_form_with_topology", () -> OperatorClassRegistry.registerClass(Both.class));
    }

    @Test
    @DisplayName("A short form whose tasklet name differs from its id is refused as topology.short_form_name_mismatch")
    void aShortFormWhoseTaskletNameDiffersFromItsIdIsRefused() {
        FlezhaException refused =
                Refused.by("topology.short_form_name_mismatch", () -> OperatorClassRegistry.registerClass(Tally.class));
        assertThat(refused.details()).containsEntry("factory_id", "counter").containsEntry("name", "tally");
    }

    @Test
    @DisplayName("A sink on a Void output is refused as topology.sink_without_output")
    void aSinkOnAVoidOutputIsRefused() {
        Refused.by("topology.sink_without_output", () -> OperatorClassRegistry.registerClass(Drain.class));
    }

    @Test
    @DisplayName("The author's class is the class the wiring names")
    void theAuthorsClassIsTheClassTheWiringNames() {
        OperatorClassRegistry.registerClass(WiredOperator.class);
        assertThat(OperatorClassRegistry.getTopology("wired").tasklets())
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.name()).isEqualTo("track_prices");
                    assertThat(t.taskletClass()).isEqualTo(TrackPrices.class);
                });
    }

    @Test
    @DisplayName("Only the author's classes exist: no annotation processor ships, and no class is generated")
    void onlyTheAuthorsClassesExist() throws Exception {
        OperatorClassRegistry.registerClass(Echo.class);

        assertThat(Collections.list(ShortFormTest.class
                        .getClassLoader()
                        .getResources("META-INF/services/javax.annotation.processing.Processor")))
                .noneMatch(url -> url.toString().contains("flezha"));
        for (String suffix : List.of("Tasklet", "Operator", "Handle", "Change")) {
            assertThatThrownBy(() -> Class.forName(Echo.class.getName() + suffix))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    @DisplayName("The short form takes the settings tree the full form takes")
    void theShortFormTakesTheTree() {
        OperatorClassRegistry.registerClass(Ticker.class);
        Map<String, Object> declaration =
                OperatorClassRegistry.getTopology("ticker").declaration();

        assertThat(declaration)
                .containsEntry("description", "every setting written")
                .containsEntry("max_duration_ms", 60_000L)
                .containsEntry("durable", Map.of("local", Map.of("sleep_after", 2_000L)));
        assertThat(declaration.get("run"))
                .isEqualTo(Map.of(
                        "flow",
                        Map.of(
                                "exactly",
                                Map.of(
                                        "effectively_once",
                                        Map.of(
                                                "ack",
                                                Map.of("checkpoint", Map.of("every", 100, "outputs", "as_produced")),
                                                "beyond_window",
                                                "process_as_arrival")))));
    }

    @Test
    @DisplayName("A short form kept in memory never sleeps, and one with no settings leaves its sleep to its run")
    void theShortFormSpellsTheDurableLevels() {
        OperatorClassRegistry.registerClass(Volatile.class);
        OperatorClassRegistry.registerClass(Echo.class);

        assertThat(OperatorClassRegistry.getTopology("volatile").declaration()).containsEntry("durable", false);
        assertThat(OperatorClassRegistry.getTopology("echo").declaration())
                .containsEntry("durable", "local")
                .containsEntry("run", Map.of("flow", Map.of("exactly", "at_least_once")));
    }

    @Test
    @DisplayName("Two branches of one setting written at once are refused naming the setting")
    void twoBranchesAreRefused() {
        FlezhaException refused =
                Refused.by("topology.invalid_setting", () -> OperatorClassRegistry.registerClass(TwoRuns.class));
        assertThat(refused.details()).containsEntry("setting", "run");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stepsBlock(OperatorTopology topology) {
        return ((Map<String, Map<String, Object>>) topology.declaration().get("run")).get("steps");
    }

    @Operator(
            id = "ticker",
            description = "every setting written",
            maxDurationMs = 60_000,
            run = @Run(flow = @Flow(effectivelyOnce = @EffectivelyOnce(checkpoint = @Checkpoint(every = 100)))),
            durable = @Durable(local = @Level(sleepAfterMs = 2_000)))
    public static final class Ticker implements Tasklet<String, Ticker.Count, Void> {
        public static final class Count {}

        @Override
        public void run(String input, Ctx<Count, Void> ctx) {}
    }

    @Operator(id = "volatile", durable = @Durable(false))
    public static final class Volatile extends EchoBody {}

    @Operator(id = "two_runs", run = @Run(flow = @Flow, steps = @Steps))
    public static final class TwoRuns extends EchoBody {}

    @Operator(id = "echo", sink = "out")
    public static final class Echo implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("echo: " + input);
        }
    }

    @Operator(id = "place", sink = "placed", input = "order", run = @Run(steps = @Steps))
    public static final class Place implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("placed " + input);
        }
    }

    @Operator(id = "chat", sink = "replies", run = @Run(steps = @Steps(each = Each.PER_INPUT)))
    public static final class Chat implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    @Operator(id = "counter", input = "ticks")
    @Tasklet.Spec(name = "counter", maxCells = 10)
    public static final class Counter implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}
    }

    @Operator(id = "prices", sink = "out", input = "prices")
    public static final class Prices extends EchoBody {}

    @Operator(id = "never_registered", sink = "out")
    public static final class NeverRegistered extends EchoBody {}

    @Operator(sink = "out")
    public static final class Upcase extends EchoBody {}

    @Operator(id = "counter")
    @Tasklet.Spec(name = "tally")
    public static final class Tally implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}
    }

    @Operator(id = "drain", sink = "out")
    public static final class Drain implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}
    }

    @Operator(id = "both")
    public static final class Both implements Tasklet<String, Void, Void>, com.flezha.sdk.operator.Operator {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}

        @Override
        public String factoryId() {
            return "both";
        }

        @Override
        public void channels(ChannelBuilder c) {}
    }

    public abstract static class EchoBody implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    public record Trade(String pair, double price) {}

    @Tasklet.Spec(name = "track_prices")
    public static final class TrackPrices implements Tasklet<Trade, Void, Trade> {
        @Override
        public void run(Trade trade, Ctx<Void, Trade> ctx) throws TaskletException {
            ctx.emit(trade);
        }
    }

    public static final class WiredOperator extends Op {
        public WiredOperator() {
            super(
                    "wired",
                    c -> c.input(Trade.class, "trades").into(TrackPrices.class).sink("moves"));
        }
    }
}
