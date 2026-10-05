package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.authoring.Relay;
import com.flezha.sdk.authoring.Relay.Checkpoint;
import com.flezha.sdk.authoring.Relay.Durable;
import com.flezha.sdk.authoring.Relay.EffectivelyOnce;
import com.flezha.sdk.authoring.Relay.Flow;
import com.flezha.sdk.authoring.Relay.Level;
import com.flezha.sdk.authoring.Relay.Run;
import com.flezha.sdk.authoring.Relay.Steps;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.relay.Relay.Each;
import com.flezha.sdk.runtime.Refused;
import com.flezha.sdk.runtime.relay.RelayClassRegistry;
import com.flezha.sdk.runtime.relay.RelayTopology;
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
    @DisplayName("An annotated class is a whole flow relay: entry `input`, the tasklet, output `out`")
    void anAnnotatedClassIsAWholeRelay() {
        assertThat(RelayClassRegistry.registerClass(Echo.class)).isEqualTo("echo");
        RelayTopology topology = RelayClassRegistry.getTopology("echo");

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
    @DisplayName("An annotated class is a whole steps relay, run once on its named entry")
    void anAnnotatedClassIsAWholeStepsRelay() {
        RelayClassRegistry.registerClass(Place.class);
        RelayTopology topology = RelayClassRegistry.getTopology("place");

        assertThat(topology.lookupChannelId("order")).isNotNull();
        assertThat(stepsBlock(topology)).containsEntry("each", "once").containsEntry("on_failure", "undo_all");

        ItemEffect effect = soleEffect(work("place", 0, Optional.empty(), 0, input(0, "o-1", 1)));
        assertThat(effect.outputs()).singleElement().satisfies(out -> {
            assertThat(out.target()).isEqualTo(new OutputTarget.Plugin("placed"));
            assertThat(new String(out.payload(), StandardCharsets.UTF_8)).isEqualTo("placed o-1");
        });
    }

    @Test
    @DisplayName("A short-form steps relay declares its loop shape")
    void aShortFormRelayDeclaresItsEach() {
        RelayClassRegistry.registerClass(Chat.class);
        assertThat(stepsBlock(RelayClassRegistry.getTopology("chat"))).containsEntry("each", "per_input");
    }

    @Test
    @DisplayName("A short form with no output is declared: its entry channel into the tasklet and no plugin channel")
    void aShortFormWithNoOutputIsDeclared() {
        RelayClassRegistry.registerClass(Counter.class);
        RelayTopology topology = RelayClassRegistry.getTopology("counter");

        assertThat(topology.channels()).singleElement().satisfies(c -> assertThat(c.name())
                .isEqualTo("ticks"));
        assertThat(topology.channels()).noneMatch(c -> c.pluginSink().isPresent());
    }

    @Test
    @DisplayName("The entry channel keeps its default name; the annotation names the entry channel")
    void theEntryChannelIsNamedByTheAnnotationOrInput() {
        RelayClassRegistry.registerClass(Echo.class);
        RelayClassRegistry.registerClass(Prices.class);

        assertThat(RelayClassRegistry.getTopology("echo").lookupChannelId("input"))
                .isNotNull();
        assertThat(RelayClassRegistry.getTopology("prices").lookupChannelId("prices"))
                .isNotNull();
    }

    @Test
    @DisplayName("Nothing registers itself: an annotated class never registered declares no relay")
    void nothingRegistersItself() {
        assertThat(RelayClassRegistry.factoryIds()).doesNotContain("never_registered");
    }

    @Test
    @DisplayName("A short form takes settings from @Tasklet.Spec")
    void aShortFormTakesSettingsFromTaskletSpec() {
        RelayClassRegistry.registerClass(Counter.class);
        assertThat(RelayClassRegistry.getTopology("counter").tasklets())
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
                Refused.by("topology.missing_factory_id", () -> RelayClassRegistry.registerClass(Upcase.class));
        assertThat(refused.details()).containsEntry("relay", Upcase.class.getName());
    }

    @Test
    @DisplayName("An annotated class implementing a relay interface is refused as topology.short_form_with_topology")
    void anAnnotatedClassImplementingARelayInterfaceIsRefused() {
        Refused.by("topology.short_form_with_topology", () -> RelayClassRegistry.registerClass(Both.class));
    }

    @Test
    @DisplayName("A short form whose tasklet name differs from its id is refused as topology.short_form_name_mismatch")
    void aShortFormWhoseTaskletNameDiffersFromItsIdIsRefused() {
        FlezhaException refused =
                Refused.by("topology.short_form_name_mismatch", () -> RelayClassRegistry.registerClass(Tally.class));
        assertThat(refused.details()).containsEntry("factory_id", "counter").containsEntry("name", "tally");
    }

    @Test
    @DisplayName("An output on a Void output type is refused as topology.output_without_type")
    void anOutputOnAVoidOutputIsRefused() {
        Refused.by("topology.output_without_type", () -> RelayClassRegistry.registerClass(Drain.class));
    }

    @Test
    @DisplayName("The author's class is the class the wiring names")
    void theAuthorsClassIsTheClassTheWiringNames() {
        RelayClassRegistry.registerClass(WiredRelay.class);
        assertThat(RelayClassRegistry.getTopology("wired").tasklets())
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.name()).isEqualTo("track_prices");
                    assertThat(t.taskletClass()).isEqualTo(TrackPrices.class);
                });
    }

    @Test
    @DisplayName("Only the author's classes exist: no annotation processor ships, and no class is generated")
    void onlyTheAuthorsClassesExist() throws Exception {
        RelayClassRegistry.registerClass(Echo.class);

        assertThat(Collections.list(ShortFormTest.class
                        .getClassLoader()
                        .getResources("META-INF/services/javax.annotation.processing.Processor")))
                .noneMatch(url -> url.toString().contains("flezha"));
        for (String suffix : List.of("Tasklet", "Relay", "Handle", "Change")) {
            assertThatThrownBy(() -> Class.forName(Echo.class.getName() + suffix))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    @DisplayName("The short form takes the settings tree the full form takes")
    void theShortFormTakesTheTree() {
        RelayClassRegistry.registerClass(Ticker.class);
        Map<String, Object> declaration =
                RelayClassRegistry.getTopology("ticker").declaration();

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
        RelayClassRegistry.registerClass(Volatile.class);
        RelayClassRegistry.registerClass(Echo.class);

        assertThat(RelayClassRegistry.getTopology("volatile").declaration()).containsEntry("durable", false);
        assertThat(RelayClassRegistry.getTopology("echo").declaration())
                .containsEntry("durable", "local")
                .containsEntry("run", Map.of("flow", Map.of("exactly", "at_least_once")));
    }

    @Test
    @DisplayName("Two branches of one setting written at once are refused naming the setting")
    void twoBranchesAreRefused() {
        FlezhaException refused =
                Refused.by("topology.invalid_setting", () -> RelayClassRegistry.registerClass(TwoRuns.class));
        assertThat(refused.details()).containsEntry("setting", "run");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stepsBlock(RelayTopology topology) {
        return ((Map<String, Map<String, Object>>) topology.declaration().get("run")).get("steps");
    }

    @Relay(
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

    @Relay(id = "volatile", durable = @Durable(false))
    public static final class Volatile extends EchoBody {}

    @Relay(id = "two_runs", run = @Run(flow = @Flow, steps = @Steps))
    public static final class TwoRuns extends EchoBody {}

    @Relay(id = "echo", output = "out")
    public static final class Echo implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("echo: " + input);
        }
    }

    @Relay(id = "place", output = "placed", input = "order", run = @Run(steps = @Steps))
    public static final class Place implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("placed " + input);
        }
    }

    @Relay(id = "chat", output = "replies", run = @Run(steps = @Steps(each = Each.PER_INPUT)))
    public static final class Chat implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    @Relay(id = "counter", input = "ticks")
    @Tasklet.Spec(name = "counter", maxCells = 10)
    public static final class Counter implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}
    }

    @Relay(id = "prices", output = "out", input = "prices")
    public static final class Prices extends EchoBody {}

    @Relay(id = "never_registered", output = "out")
    public static final class NeverRegistered extends EchoBody {}

    @Relay(output = "out")
    public static final class Upcase extends EchoBody {}

    @Relay(id = "counter")
    @Tasklet.Spec(name = "tally")
    public static final class Tally implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}
    }

    @Relay(id = "drain", output = "out")
    public static final class Drain implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}
    }

    @Relay(id = "both")
    public static final class Both implements Tasklet<String, Void, Void>, com.flezha.sdk.relay.Relay {
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

    public static final class WiredRelay extends Op {
        public WiredRelay() {
            super(
                    "wired",
                    c -> c.input(Trade.class, "trades").into(TrackPrices.class).output("moves"));
        }
    }
}
