package com.flezha.sdk.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.authoring.BoundedOperator;
import com.flezha.sdk.authoring.UnboundedOperator;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.operator.LoopMode;
import com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry;
import com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry;
import com.flezha.sdk.runtime.operator.UnboundedOperatorTopology;
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
    @DisplayName("An annotated class is a whole unbounded operator: entry `input`, the tasklet, sink `out`")
    void anAnnotatedClassIsAWholeUnboundedOperator() {
        assertThat(UnboundedOperatorClassRegistry.registerClass(Echo.class)).isEqualTo("echo");
        UnboundedOperatorTopology topology = UnboundedOperatorClassRegistry.getTopology("echo");

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
    @DisplayName("An annotated class is a whole bounded operator, run once on its named entry")
    void anAnnotatedClassIsAWholeBoundedOperator() {
        BoundedOperatorClassRegistry.registerClass(Place.class);
        UnboundedOperatorTopology topology = BoundedOperatorClassRegistry.getTopology("place");

        assertThat(topology.lookupChannelId("order")).isNotNull();
        assertThat(boundedBlock(topology)).containsEntry("loop_mode", "once");

        ItemEffect effect = soleEffect(work("place", 0, Optional.empty(), 0, input(0, "o-1", 1)));
        assertThat(effect.outputs()).singleElement().satisfies(out -> {
            assertThat(out.target()).isEqualTo(new OutputTarget.Plugin("placed"));
            assertThat(new String(out.payload(), StandardCharsets.UTF_8)).isEqualTo("placed o-1");
        });
    }

    @Test
    @DisplayName("A short-form bounded operator declares its loop mode")
    void aShortFormBoundedOperatorDeclaresItsLoopMode() {
        BoundedOperatorClassRegistry.registerClass(Chat.class);
        assertThat(boundedBlock(BoundedOperatorClassRegistry.getTopology("chat")))
                .containsEntry("loop_mode", "per_input");
    }

    @Test
    @DisplayName("A short form with no sink is declared: its entry channel into the tasklet and no plugin channel")
    void aShortFormWithNoSinkIsDeclared() {
        UnboundedOperatorClassRegistry.registerClass(Counter.class);
        UnboundedOperatorTopology topology = UnboundedOperatorClassRegistry.getTopology("counter");

        assertThat(topology.channels()).singleElement().satisfies(c -> assertThat(c.name())
                .isEqualTo("ticks"));
        assertThat(topology.channels()).noneMatch(c -> c.pluginSink().isPresent());
    }

    @Test
    @DisplayName("The entry channel keeps its default name; the annotation names the entry channel")
    void theEntryChannelIsNamedByTheAnnotationOrInput() {
        UnboundedOperatorClassRegistry.registerClass(Echo.class);
        UnboundedOperatorClassRegistry.registerClass(Prices.class);

        assertThat(UnboundedOperatorClassRegistry.getTopology("echo").lookupChannelId("input"))
                .isNotNull();
        assertThat(UnboundedOperatorClassRegistry.getTopology("prices").lookupChannelId("prices"))
                .isNotNull();
    }

    @Test
    @DisplayName("Nothing registers itself: an annotated class never registered declares no operator")
    void nothingRegistersItself() {
        assertThat(UnboundedOperatorClassRegistry.factoryIds()).doesNotContain("never_registered");
    }

    @Test
    @DisplayName("A short form takes settings from @Tasklet.Spec")
    void aShortFormTakesSettingsFromTaskletSpec() {
        UnboundedOperatorClassRegistry.registerClass(Counter.class);
        assertThat(UnboundedOperatorClassRegistry.getTopology("counter").tasklets())
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.name()).isEqualTo("counter");
                    assertThat(t.maxCells()).hasValue(10L);
                });
    }

    @Test
    @DisplayName("An annotation with no id is refused naming the required id")
    void anAnnotationWithNoIdIsRefused() {
        assertThatThrownBy(() -> UnboundedOperatorClassRegistry.registerClass(Upcase.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Upcase.class.getName())
                .hasMessageContaining("requires `id`");
    }

    @Test
    @DisplayName("An annotated class implementing an operator interface is refused as ShortFormWithTopology")
    void anAnnotatedClassImplementingAnOperatorInterfaceIsRefused() {
        assertThatThrownBy(() -> UnboundedOperatorClassRegistry.registerClass(Both.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("ShortFormWithTopology")
                .hasMessageContaining(Both.class.getName());
    }

    @Test
    @DisplayName("A short form whose tasklet name differs from its id is refused as ShortFormNameMismatch")
    void aShortFormWhoseTaskletNameDiffersFromItsIdIsRefused() {
        assertThatThrownBy(() -> UnboundedOperatorClassRegistry.registerClass(Tally.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("ShortFormNameMismatch")
                .hasMessageContaining("`counter`")
                .hasMessageContaining("`tally`");
    }

    @Test
    @DisplayName("A sink on a Void output is refused as SinkWithoutOutput")
    void aSinkOnAVoidOutputIsRefused() {
        assertThatThrownBy(() -> UnboundedOperatorClassRegistry.registerClass(Drain.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("SinkWithoutOutput")
                .hasMessageContaining(Drain.class.getName());
    }

    @Test
    @DisplayName("The author's class is the class the wiring names")
    void theAuthorsClassIsTheClassTheWiringNames() {
        UnboundedOperatorClassRegistry.registerClass(WiredOperator.class);
        assertThat(UnboundedOperatorClassRegistry.getTopology("wired").tasklets())
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.name()).isEqualTo("track_prices");
                    assertThat(t.taskletClass()).isEqualTo(TrackPrices.class);
                });
    }

    @Test
    @DisplayName("Only the author's classes exist: no annotation processor ships, and no class is generated")
    void onlyTheAuthorsClassesExist() throws Exception {
        UnboundedOperatorClassRegistry.registerClass(Echo.class);

        assertThat(Collections.list(ShortFormTest.class
                        .getClassLoader()
                        .getResources("META-INF/services/javax.annotation.processing.Processor")))
                .noneMatch(url -> url.toString().contains("flezha"));
        for (String suffix : List.of("Tasklet", "UnboundedOperator", "Handle", "Change")) {
            assertThatThrownBy(() -> Class.forName(Echo.class.getName() + suffix))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> boundedBlock(UnboundedOperatorTopology topology) {
        return (Map<String, Object>) topology.declaration().get("bounded");
    }

    @UnboundedOperator(id = "echo", sink = "out")
    public static final class Echo implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("echo: " + input);
        }
    }

    @BoundedOperator(id = "place", sink = "placed", input = "order")
    public static final class Place implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit("placed " + input);
        }
    }

    @BoundedOperator(id = "chat", sink = "replies", loopMode = LoopMode.PER_INPUT)
    public static final class Chat implements Tasklet<String, Void, String> {
        @Override
        public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
            ctx.emit(input);
        }
    }

    @UnboundedOperator(id = "counter", input = "ticks")
    @Tasklet.Spec(name = "counter", maxCells = 10)
    public static final class Counter implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}
    }

    @UnboundedOperator(id = "prices", sink = "out", input = "prices")
    public static final class Prices extends EchoBody {}

    @UnboundedOperator(id = "never_registered", sink = "out")
    public static final class NeverRegistered extends EchoBody {}

    @UnboundedOperator(sink = "out")
    public static final class Upcase extends EchoBody {}

    @UnboundedOperator(id = "counter")
    @Tasklet.Spec(name = "tally")
    public static final class Tally implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}
    }

    @UnboundedOperator(id = "drain", sink = "out")
    public static final class Drain implements Tasklet<String, Void, Void> {
        @Override
        public void run(String input, Ctx<Void, Void> ctx) {}
    }

    @UnboundedOperator(id = "both")
    public static final class Both implements Tasklet<String, Void, Void>, com.flezha.sdk.operator.UnboundedOperator {
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
