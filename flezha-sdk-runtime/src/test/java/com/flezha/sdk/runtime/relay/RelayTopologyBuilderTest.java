package com.flezha.sdk.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.runtime.Refused;
import com.flezha.sdk.runtime.relay.builder.RelayTopologyBuilder;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RelayTopologyBuilderTest {

    public record Trade(String symbol, double price) {}

    public record Candle(String symbol, double open, double close) {}

    @Nested
    class WiringValidation {

        @Test
        @DisplayName("single-mode entry channel + consumer compiles and freezes")
        void singleModeOk() {
            RelayTopology topology = RelayTopologyBuilder.build(new SingleModeRelay());
            assertThat(topology.factoryId()).isEqualTo("single_mode");
            assertThat(topology.channels()).hasSize(1);
            assertThat(topology.channels().get(0).kind()).isEqualTo(ChannelKind.ENTRY);
            assertThat(topology.tasklets()).hasSize(1);
            assertThat(topology.tasklets().get(0).cellKeys()).isEmpty();
        }

        @Test
        @DisplayName("static-mode tasklet allocates one descriptor with the declared keys")
        void staticModeAllocatesCells() {
            RelayTopology topology = RelayTopologyBuilder.build(new StaticModeRelay());
            assertThat(topology.tasklets().get(0).cellKeys()).containsExactly("a", "b", "c", "d");
            assertThat(topology.tasklets().get(0).cellsForOverridden()).isTrue();
        }

        @Test
        @DisplayName("a typed entry channel is accepted and decodes at the edge")
        void typedEntryChannelIsAcceptedAsAParser() {
            // `sdk-graph-authoring`: any entry type is legal; non-byte[]/String
            // entries decode at the edge (default Jackson) and are flagged as
            // real parsers so a failing decode rides the retry→DLQ ladder.
            var topology = RelayTopologyBuilder.build(new BadEntryTypeRelay());
            assertThat(topology.channels().get(0).entryParsed()).isTrue();
            assertThat(topology.channels().get(0).entryParser()).isPresent();
        }

        @Test
        @DisplayName("wiring mismatch fails registration with diagnosable error")
        void wiringMismatchRejected() {
            FlezhaException refused = Refused.by(
                    "topology.payload_type_mismatch", () -> RelayTopologyBuilder.build(new TypeMismatchRelay()));
            assertThat(Refused.details(refused, "topology.payload_type_mismatch"))
                    .containsEntry("channel", "trade.parsed");
        }

        @Test
        @DisplayName("static-mode tasklet that declares no cellsFor is rejected")
        void staticWithoutCellsForRejected() {
            Refused.by("topology.cells_for_missing", () -> RelayTopologyBuilder.build(new MissingCellsForRelay()));
        }

        @Test
        @DisplayName("a type argument still generic at the tasklet class is refused at definition time")
        void unresolvedTypeTokenRejected() {
            // The defaults read the implements clause; what they cannot read
            // they refuse by name here, rather than guessing and failing at
            // dispatch with a type mismatch far from this declaration.
            FlezhaException refused = Refused.by(
                    "topology.unresolved_type_argument", () -> RelayTopologyBuilder.build(new UnresolvedTokenRelay()));
            assertThat(Refused.details(refused, "topology.unresolved_type_argument"))
                    .containsEntry("tasklet", UnresolvedInputTasklet.class.getName())
                    .containsEntry("parameter", "Input");
        }

        @Test
        @DisplayName("a throwing tasklet constructor is collected with the other refusals, naming the author's error")
        void throwingTaskletConstructorIsCollectedWithTheOtherRefusals() {
            FlezhaException refused =
                    Refused.by("topology.two_clocks", () -> RelayTopologyBuilder.build(new ThrowingTaskletRelay()));
            assertThat(refused.issueCodes())
                    .containsExactlyInAnyOrder("topology.two_clocks", "topology.class_not_constructible");
            assertThat(Refused.details(refused, "topology.class_not_constructible")
                            .get("reason"))
                    .asString()
                    .contains(ThrowingConstructorTasklet.REFUSAL);
        }

        @Test
        @DisplayName("a keyed edge builds a dynamic-mode consumer (Cells are created per key at dispatch)")
        void dynamicModeOnlyBuilds() {
            // The keying is on the WIRING: the tasklet declares nothing, and
            // the edge's key function is what classifies it as dynamic.
            RelayTopology topology = RelayTopologyBuilder.build(new DynamicModeOnlyRelay());
            assertThat(topology.tasklets()).hasSize(1);
            assertThat(topology.tasklets().get(0).dynamic()).isTrue();
            assertThat(topology.tasklets().get(0).cellKeys()).isEmpty();
            assertThat(topology.channels().get(0).consumers().get(0).key()).isPresent();
        }
    }

    // ---- Test relays / tasklets -----------------------------------------------

    /** A concrete tasklet class that leaves its own Input open. */
    @Tasklet.Spec(name = "unresolved_input")
    public static final class UnresolvedInputTasklet<I> implements Tasklet<I, Void, String> {

        @Override
        public void run(I input, Ctx<Void, String> ctx) {}
    }

    public static final class UnresolvedTokenRelay implements Relay {
        @Override
        public String factoryId() {
            return "unresolved_token";
        }

        @Override
        @SuppressWarnings("unchecked")
        public void channels(ChannelBuilder c) {
            // A generic tasklet class has a raw class literal, so wiring it
            // takes a cast — which is the author's first hint that the runtime
            // will have nothing concrete to read either.
            c.input(String.class, "in")
                    .into((Class<? extends Tasklet<String, ?, String>>) (Class<?>) UnresolvedInputTasklet.class);
        }
    }

    @Tasklet.Spec(name = "throwing_constructor")
    public static final class ThrowingConstructorTasklet implements Tasklet<String, Void, String> {
        static final String REFUSAL = "the author's own refusal";

        public ThrowingConstructorTasklet() throws IOException {
            throw new IOException(REFUSAL);
        }

        @Override
        public void run(String input, Ctx<Void, String> ctx) {}
    }

    public static final class ThrowingTaskletRelay implements Relay {
        @Override
        public String factoryId() {
            return "throwing_tasklet";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in")
                    .into(ClockAndKeyedValidationTest.TwoClocksTask.class)
                    .into(ThrowingConstructorTasklet.class);
        }
    }

    @Tasklet.Spec(name = "single_tasklet")
    public static final class SingleTasklet implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) {}
    }

    public static final class SingleModeRelay implements Relay {
        @Override
        public String factoryId() {
            return "single_mode";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(SingleTasklet.class);
        }
    }

    @Tasklet.Spec(
            name = "static_tasklet",
            keys = {"a", "b", "c", "d"})
    public static final class StaticTasklet implements Tasklet<String, Void, String> {

        @Override
        public List<String> cellsFor(String input) {
            return List.of(input.substring(0, 1));
        }

        @Override
        public void run(String input, Ctx<Void, String> ctx) {}
    }

    public static final class StaticModeRelay implements Relay {
        @Override
        public String factoryId() {
            return "static_mode";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(StaticTasklet.class);
        }
    }

    public static final class BadEntryTypeRelay implements Relay {
        @Override
        public String factoryId() {
            return "bad_entry";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(Trade.class, "trade.raw").into(TradeTasklet.class);
        }
    }

    @Tasklet.Spec(name = "trade_tasklet")
    public static final class TradeTasklet implements Tasklet<Trade, Void, String> {

        @Override
        public void run(Trade input, Ctx<Void, String> ctx) {}
    }

    @Tasklet.Spec(name = "candle_tasklet")
    public static final class CandleTasklet implements Tasklet<Candle, Void, String> {

        @Override
        public void run(Candle input, Ctx<Void, String> ctx) {}
    }

    @Tasklet.Spec(name = "trade_producer")
    public static final class TradeProducerTasklet implements Tasklet<String, Void, Trade> {

        @Override
        public void run(String input, Ctx<Void, Trade> ctx) throws TaskletException {}
    }

    public static final class TypeMismatchRelay implements Relay {
        @Override
        public String factoryId() {
            return "type_mismatch";
        }

        @Override
        public void channels(ChannelBuilder c) {
            ChannelDeclMismatch.declareTradeIntoCandle(
                    c.input(String.class, "input").into(TradeProducerTasklet.class));
        }
    }

    /** Helper to keep the erasure-mismatch construction off the relay. */
    private static final class ChannelDeclMismatch {
        @SuppressWarnings({"unchecked", "rawtypes"})
        static void declareTradeIntoCandle(com.flezha.sdk.channel.Stream<Trade> stream) {
            ((com.flezha.sdk.channel.Stream) stream.named("trade.parsed")).into((Class) CandleTasklet.class);
        }
    }

    @Tasklet.Spec(
            name = "missing_pfo_tasklet",
            keys = {"a", "b"})
    public static final class MissingCellsForTasklet implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) {}
    }

    public static final class MissingCellsForRelay implements Relay {
        @Override
        public String factoryId() {
            return "missing_pfo_relay";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(MissingCellsForTasklet.class);
        }
    }

    @Tasklet.Spec(name = "dynamic_only_tasklet")
    public static final class DynamicModeOnlyTasklet implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) {}
    }

    public static final class DynamicModeOnlyRelay implements Relay {
        @Override
        public String factoryId() {
            return "dynamic_only_relay";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").keyBy(input -> input).into(DynamicModeOnlyTasklet.class);
        }
    }

    // ---- sdk-graph-authoring pins (mirror of the Rust relay_topology tests) ----

    @Nested
    class GraphAuthoring {

        @Test
        @DisplayName("branch() builds the identical topology to handle reuse")
        void branchBuildsIdenticalTopology() {
            var byHandles = RelayTopologyBuilder.build("g1", c -> {
                var validated = c.input(String.class, "in").into(SingleTasklet.class);
                validated.into(GraphStage1.class);
                validated.into(GraphStage2.class);
            });
            var byBranch = RelayTopologyBuilder.build("g2", c -> c.input(String.class, "in")
                    .into(SingleTasklet.class)
                    .branch(b -> b.into(GraphStage1.class), b -> b.into(GraphStage2.class)));
            assertThat(shape(byBranch)).isEqualTo(shape(byHandles));
        }

        @Test
        @DisplayName("branch() with fewer than two arms is refused")
        void branchWithOneArmRefused() {
            assertThatThrownBy(() -> RelayTopologyBuilder.build("g3", c -> c.input(String.class, "in")
                            .into(SingleTasklet.class)
                            .branch(b -> b.into(GraphStage1.class))))
                    .isInstanceOfSatisfying(
                            FlezhaException.class, e -> assertThat(Refused.details(e, "topology.branch_too_few_arms"))
                                    .containsEntry("arms", 1));
        }

        @Test
        @DisplayName("named nodes reuse one class at two positions; unnamed mentions unify into a fan-in")
        void namedNodesAndFanIn() {
            var t = RelayTopologyBuilder.build("g4", c -> {
                var validated = c.input(String.class, "in").into(SingleTasklet.class);
                validated.into(GraphStage1.class, "fraud_filter").into(GraphMerge.class);
                validated.into(GraphStage1.class, "spam_filter").into(GraphMerge.class);
            });
            assertThat(t.tasklets().stream().map(o -> o.name()).toList())
                    .containsExactly("single_tasklet", "fraud_filter", "graph_merge", "spam_filter");
            // Derived channel names are the node identities; the merge is ONE node.
            assertThat(t.nameToId()).containsKeys("fraud_filter", "spam_filter");
            long merges = t.tasklets().stream()
                    .filter(o -> o.name().equals("graph_merge"))
                    .count();
            assertThat(merges).isEqualTo(1);
        }

        @Test
        @DisplayName("a node identity collision is refused")
        void nodeIdentityCollisionRefused() {
            assertThatThrownBy(() -> RelayTopologyBuilder.build("g5", c -> {
                        var validated = c.input(String.class, "in").into(SingleTasklet.class);
                        validated.into(GraphStage2.class, "graph_stage1");
                        validated.into(GraphStage1.class);
                    }))
                    .isInstanceOfSatisfying(FlezhaException.class, e -> assertThat(
                                    Refused.details(e, "topology.node_identity_collision"))
                            .containsEntry("name", "graph_stage1"));
        }

        @Test
        @DisplayName("channel names derive from the producer identity; .named overrides; collisions are refused")
        void derivedNamesAndCollision() {
            var t = RelayTopologyBuilder.build("g6", c -> c.input(String.class, "in")
                    .into(SingleTasklet.class)
                    .into(GraphStage1.class)
                    .named("orders.validated")
                    .into(GraphStage2.class));
            assertThat(t.nameToId()).containsKeys("single_tasklet", "orders.validated");

            assertThatThrownBy(() -> RelayTopologyBuilder.build("g7", c -> c.input(String.class, "single_tasklet")
                            .into(SingleTasklet.class)
                            .into(GraphStage1.class)))
                    .isInstanceOfSatisfying(
                            FlezhaException.class, e -> assertThat(Refused.details(e, "topology.duplicate_channel"))
                                    .containsEntry("channel", "single_tasklet"));
        }

        @Test
        @DisplayName("a cycle is refused")
        void cycleRefused() {
            assertThatThrownBy(() -> RelayTopologyBuilder.build("g8", c -> c.input(String.class, "in")
                            .into(GraphStage1.class)
                            .into(GraphStage2.class)
                            .into(GraphStage1.class)))
                    .isInstanceOfSatisfying(FlezhaException.class, e -> assertThat(e.issueCodes())
                            .contains("topology.cycle"));
        }

        @Test
        @DisplayName("multiple entry channels build")
        void multipleEntryChannelsBuild() {
            // One relay may declare several entry channels — one per
            // external source (e.g. a market pipeline reading a trade feed and
            // an order-book feed). The controller routes each frame to its
            // entry by metadata["channel"]. Both entries and subgraphs resolve.
            var t = RelayTopologyBuilder.build("g9", c -> {
                c.input(String.class, "in_a").into(GraphStage1.class);
                c.input(String.class, "in_b").into(GraphStage2.class);
            });
            var entries = t.channels().stream()
                    .filter(ch -> ch.kind() == ChannelKind.ENTRY)
                    .map(ch -> ch.name())
                    .toList();
            assertThat(entries).containsExactlyInAnyOrder("in_a", "in_b");
        }

        @Test
        @DisplayName("a custom entry parser replaces the default Jackson decode")
        void customParserReplacesDefaultJson() {
            var t = RelayTopologyBuilder.build(
                    "g10", c -> c.input(Trade.class, "orders", bytes -> new Trade(new String(bytes), 1.0))
                            .into(TradeTasklet.class));
            var entry = t.channels().get(0);
            assertThat(entry.entryParsed()).isTrue();
            Object decoded = entry.entryParser().orElseThrow().apply("BTC".getBytes());
            assertThat(decoded).isEqualTo(new Trade("BTC", 1.0));
        }

        @Test
        @DisplayName("byte[] and String entries stay passthrough (not parsers)")
        void bytesAndStringEntriesStayPassthrough() {
            var t = RelayTopologyBuilder.build(
                    "g11", c -> c.input(String.class, "in").into(SingleTasklet.class));
            assertThat(t.channels().get(0).entryParsed()).isFalse();
        }

        @Test
        @DisplayName("a stream handle used after the freeze throws naming the relay")
        void handleAfterFreezeThrows() {
            var leaked = new java.util.concurrent.atomic.AtomicReference<com.flezha.sdk.channel.Stream<String>>();
            RelayTopologyBuilder.build(
                    "g12", c -> leaked.set(c.input(String.class, "in").into(SingleTasklet.class)));
            assertThatThrownBy(() -> leaked.get().into(GraphStage1.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("g12");
        }

        @Test
        @DisplayName("a sink with no consumers is the terminal")
        void sinkTerminalityIsDerivedFromTheWiring() {
            var terminal = RelayTopologyBuilder.build(
                    "g13",
                    c -> c.input(String.class, "in").into(SingleTasklet.class).sink("egress"));
            var hybrid = RelayTopologyBuilder.build("g15", c -> c.input(String.class, "in")
                    .into(SingleTasklet.class)
                    .sink("egress")
                    .into(GraphStage1.class));

            assertThat(sinkShape(terminal)).isEqualTo(List.of(ChannelKind.PLUGIN_BOUND.name(), "0", "egress"));
            assertThat(sinkShape(hybrid)).isEqualTo(List.of(ChannelKind.INTER_TASKLET.name(), "1", "egress"));
        }

        @Test
        @DisplayName("the sink names the channel, and the no-argument form leaves it derived")
        void sinkNamesTheChannelAndTheNoArgumentFormDoesNot() {
            var named = RelayTopologyBuilder.build(
                    "g13a",
                    c -> c.input(String.class, "in").into(SingleTasklet.class).sink("items_output"));
            var derived = RelayTopologyBuilder.build(
                    "g13b",
                    c -> c.input(String.class, "in").into(SingleTasklet.class).sink());

            assertThat(named.channels().get(1).name()).isEqualTo("items_output");
            assertThat(derived.channels().get(1).name())
                    .as("sink() derives exactly as a channel with no sink does")
                    .isEqualTo("single_tasklet");
            assertThat(derived.channels().get(1).pluginSink()).isPresent();
        }

        @Test
        @DisplayName("a response is recorded as its link and derives as a sink's end, keeping the name")
        void responseDerivesAsASinksEnd() {
            var terminal = RelayTopologyBuilder.build(
                    "g13c",
                    c -> c.input(String.class, "in").into(SingleTasklet.class).response());
            var hybrid = RelayTopologyBuilder.build("g13d", c -> c.input(String.class, "in")
                    .into(SingleTasklet.class)
                    .response()
                    .into(GraphStage1.class));

            assertThat(chainOf(terminal, 1)).isEqualTo(List.of(java.util.Map.of("link", "response")));
            var answering = terminal.channels().get(1);
            assertThat(answering.name()).as("response() takes no name").isEqualTo("single_tasklet");
            assertThat(answering.kind()).isEqualTo(ChannelKind.PLUGIN_BOUND);
            assertThat(answering.pluginSink())
                    .as("an emit on it leaves as the plugin output a sink's would")
                    .isPresent();
            assertThat(hybrid.channels().get(1).kind()).isEqualTo(ChannelKind.INTER_TASKLET);
            assertThat(hybrid.channels().get(1).consumerIds()).containsExactly(1);
            assertThat(hybrid.channels().get(1).pluginSink()).isPresent();
        }

        /** The links of the first chain declared on one channel, as the record spells them. */
        @SuppressWarnings("unchecked")
        private List<Object> chainOf(RelayTopology t, int channel) {
            var channels = (List<java.util.Map<String, Object>>) t.declaration().get("channels");
            return ((List<List<Object>>) channels.get(channel).get("chains")).get(0);
        }

        /** Everything about the `egress` channel the sink declaration decides. */
        private java.util.List<String> sinkShape(RelayTopology t) {
            var channel = t.channels().stream()
                    .filter(ch -> ch.name().equals("egress"))
                    .findFirst()
                    .orElseThrow();
            return List.of(
                    channel.kind().name(),
                    String.valueOf(channel.consumerIds().size()),
                    channel.pluginSink().map(sink -> channel.name()).orElse("<none>"));
        }

        private java.util.List<String> shape(RelayTopology t) {
            var channels = t.channels().stream()
                    .map(ch -> ch.name() + "|" + ch.kind() + "|" + ch.producer() + "|" + ch.consumerIds())
                    .toList();
            var tasks = t.tasklets().stream().map(o -> o.name()).toList();
            var all = new java.util.ArrayList<String>(channels);
            all.addAll(tasks);
            return all;
        }
    }

    /**
     * The verdict a build was refused with, or {@code null} if the build was accepted. Package
     * private: {@link ReferenceTopologyRowsTest} reads the same verdict off the same builder.
     */
    /** The topology a class with {@code run} and the graph {@code authoring} builds. */
    static RelayTopology build(String factoryId, Relay.Run run, Consumer<ChannelBuilder> authoring) {
        return RelayTopologyBuilder.build(new Relay() {
            @Override
            public String factoryId() {
                return factoryId;
            }

            @Override
            public Run run() {
                return run;
            }

            @Override
            public void channels(ChannelBuilder c) {
                authoring.accept(c);
            }
        });
    }

    static FlezhaException catchConfigException(Runnable build) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(FlezhaException.class, build::run);
    }

    /**
     * The refusals Java reports by NAME only because the core holds them: the
     * six shapes this SDK refused in its own words, or accepted, before the
     * builder started submitting its record. A verdict lists every refusal it
     * found, so a sibling name alongside the asserted one is expected.
     */
    @Nested
    class CoreRefusals {

        @Test
        @DisplayName("a zero correction window, and the two things a window rests on")
        void correctionRefusals() {
            FlezhaException refused = catchConfigException(() -> build(
                    "r1",
                    new Relay.Run.Flow(new Relay.Exactly.EffectivelyOnce().withCorrectionMs(0)),
                    c -> c.input(String.class, "in").into(SingleTasklet.class)));
            assertThat(refused.issueCodes())
                    .as("a zero window is the one shape Java accepted before the core held the rules")
                    .contains("topology.correction_window_zero", "topology.correction_without_event_time");
        }

        @Test
        @DisplayName("a zero retry budget is refused by name")
        void retryMaxZeroRefused() {
            assertThatThrownBy(() -> RelayTopologyBuilder.build(
                            "r2", c -> c.input(String.class, "in").retryMax(0).into(SingleTasklet.class)))
                    .isInstanceOfSatisfying(
                            FlezhaException.class, e -> assertThat(Refused.details(e, "topology.retry_max_zero"))
                                    .containsEntry("channel", "in"));
        }

        @Test
        @DisplayName("a second sink on one channel is refused by name; the first still registered")
        void duplicatePluginSinkRefused() {
            assertThatThrownBy(() -> RelayTopologyBuilder.build("r3", c -> c.input(String.class, "in")
                            .into(SingleTasklet.class)
                            .sink("egress")
                            .sink()))
                    .isInstanceOfSatisfying(
                            FlezhaException.class, e -> assertThat(Refused.details(e, "topology.duplicate_plugin_sink"))
                                    .containsEntry("channel", "egress"));
        }

        @Test
        @DisplayName("a sink beside a response on one channel is refused as ending twice")
        void sinkBesideResponseRefused() {
            assertThatThrownBy(() -> RelayTopologyBuilder.build("r3d", c -> c.input(String.class, "in")
                            .into(SingleTasklet.class)
                            .sink("egress")
                            .response()))
                    .isInstanceOfSatisfying(
                            FlezhaException.class, e -> assertThat(Refused.details(e, "topology.channel_ends_twice"))
                                    .containsEntry("channel", "egress"));
        }

        @Test
        @DisplayName("a channel named by both named() and sink() is refused naming both")
        void channelNamedTwiceRefused() {
            assertThatThrownBy(() -> RelayTopologyBuilder.build("r3b", c -> c.input(String.class, "in")
                            .into(SingleTasklet.class)
                            .named("a")
                            .sink("b")))
                    .isInstanceOfSatisfying(
                            FlezhaException.class, e -> assertThat(Refused.details(e, "topology.channel_named_twice"))
                                    .containsEntry("named", "a")
                                    .containsEntry("sink", "b"));
        }

        @Test
        @DisplayName("two channels naming one sink is refused as a duplicate sink name")
        void duplicateSinkNameRefused() {
            assertThatThrownBy(() -> RelayTopologyBuilder.build("r3c", c -> {
                        var in = c.input(String.class, "in");
                        in.into(SingleTasklet.class).sink("shared");
                        in.into(GraphStage1.class).sink("shared");
                    }))
                    .isInstanceOfSatisfying(
                            FlezhaException.class, e -> assertThat(Refused.details(e, "topology.duplicate_sink_name"))
                                    .containsEntry("channel", "shared"));
        }

        @Test
        @DisplayName("a plugin-bound channel that is not keyed is refused under the tier")
        void unkeyedChannelUnderTheTierRefused() {
            assertThatThrownBy(() -> build("r4", new Relay.Run.Flow(new Relay.Exactly.EffectivelyOnce()), c -> c.input(
                                    String.class, "in")
                            .into(SingleTasklet.class)
                            .sink("egress")))
                    .isInstanceOfSatisfying(FlezhaException.class, e -> assertThat(
                                    Refused.details(e, "topology.unkeyed_channel_under_effectively_once"))
                            .containsEntry("channel", "egress"));
        }
    }

    @Tasklet.Spec(name = "graph_stage1")
    public static final class GraphStage1 implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) {}
    }

    @Tasklet.Spec(name = "graph_stage2")
    public static final class GraphStage2 implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) {}
    }

    @Tasklet.Spec(name = "graph_merge")
    public static final class GraphMerge implements Tasklet<String, Void, String> {

        @Override
        public void run(String input, Ctx<Void, String> ctx) {}
    }
}
