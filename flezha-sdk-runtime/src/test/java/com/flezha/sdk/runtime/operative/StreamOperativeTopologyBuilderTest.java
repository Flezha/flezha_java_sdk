package com.flezha.sdk.runtime.operative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.operative.StreamOperative;
import com.flezha.sdk.operative.StreamOperativeConfigurationException;
import com.flezha.sdk.runtime.operative.builder.StreamOperativeTopologyBuilder;
import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.StateHandle;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StreamOperativeTopologyBuilderTest {

    public record Trade(String symbol, double price) {}

    public record Candle(String symbol, double open, double close) {}

    @Nested
    class WiringValidation {

        @Test
        @DisplayName("single-mode entry channel + consumer compiles and freezes")
        void singleModeOk() {
            StreamOperativeTopology topology = StreamOperativeTopologyBuilder.build(new SingleModeStreamOperative());
            assertThat(topology.factoryId()).isEqualTo("single_mode");
            assertThat(topology.channels()).hasSize(1);
            assertThat(topology.channels().get(0).kind()).isEqualTo(ChannelKind.ENTRY);
            assertThat(topology.tasklets()).hasSize(1);
            assertThat(topology.tasklets().get(0).cellKeys()).isEmpty();
        }

        @Test
        @DisplayName("static-mode tasklet allocates one descriptor with the declared keys")
        void staticModeAllocatesCells() {
            StreamOperativeTopology topology = StreamOperativeTopologyBuilder.build(new StaticModeStreamOperative());
            assertThat(topology.tasklets().get(0).cellKeys()).containsExactly("a", "b", "c", "d");
            assertThat(topology.tasklets().get(0).cellsForOverridden()).isTrue();
        }

        @Test
        @DisplayName("a typed entry channel is accepted and decodes at the edge")
        void typedEntryChannelIsAcceptedAsAParser() {
            // `sdk-graph-authoring`: any entry type is legal; non-byte[]/String
            // entries decode at the edge (default Jackson) and are flagged as
            // real parsers so a failing decode rides the retry→DLQ ladder.
            var topology = StreamOperativeTopologyBuilder.build(new BadEntryTypeStreamOperative());
            assertThat(topology.channels().get(0).entryParsed()).isTrue();
            assertThat(topology.channels().get(0).entryParser()).isPresent();
        }

        @Test
        @DisplayName("wiring mismatch fails registration with diagnosable error")
        void wiringMismatchRejected() {
            assertThatThrownBy(() -> StreamOperativeTopologyBuilder.build(new TypeMismatchStreamOperative()))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageContaining("trade.parsed");
        }

        @Test
        @DisplayName("static-mode tasklet without cellsForOverridden is rejected")
        void staticWithoutCellsForOverriddenRejected() {
            assertThatThrownBy(
                            () -> StreamOperativeTopologyBuilder.build(new MissingCellsForOverriddenStreamOperative()))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageContaining("CellsForMissing")
                    .hasMessageContaining("declares non-empty cell_keys() but does not override cells_for");
        }

        @Test
        @DisplayName("a type argument still generic at the tasklet class is refused at definition time")
        void unresolvedTypeTokenRejected() {
            // The defaults read the implements clause; what they cannot read
            // they refuse by name here, rather than guessing and failing at
            // dispatch with a type mismatch far from this declaration.
            assertThatThrownBy(() -> StreamOperativeTopologyBuilder.build(new UnresolvedTokenStreamOperative()))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageContaining(UnresolvedInputTasklet.class.getName())
                    .hasMessageContaining("Input");
        }

        @Test
        @DisplayName("a throwing tasklet constructor is collected with the other refusals, naming the author's error")
        void throwingTaskletConstructorIsCollectedWithTheOtherRefusals() {
            assertThatThrownBy(() -> StreamOperativeTopologyBuilder.build(new ThrowingTaskletStreamOperative()))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageStartingWith("topology rejected [Declaration, Declaration")
                    .hasMessageContaining("`no_extractor` declares the EventTimeFrom clock")
                    .hasMessageContaining(ThrowingConstructorTasklet.REFUSAL)
                    .hasMessageNotContaining("no-arg constructor");
        }

        @Test
        @DisplayName("a keyed edge builds a dynamic-mode consumer (Cells are created per key at dispatch)")
        void dynamicModeOnlyBuilds() {
            // The keying is on the WIRING: the tasklet declares nothing, and
            // the edge's key function is what classifies it as dynamic.
            StreamOperativeTopology topology =
                    StreamOperativeTopologyBuilder.build(new DynamicModeOnlyStreamOperative());
            assertThat(topology.tasklets()).hasSize(1);
            assertThat(topology.tasklets().get(0).dynamic()).isTrue();
            assertThat(topology.tasklets().get(0).cellKeys()).isEmpty();
            assertThat(topology.channels().get(0).consumers().get(0).key()).isPresent();
        }
    }

    // ---- Test operatives / tasklets -----------------------------------------------

    /** A concrete tasklet class that leaves its own Input open. */
    public static final class UnresolvedInputTasklet<I> extends StatelessTestTasklet<I, Object, String> {
        @Override
        public String name() {
            return "unresolved_input";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(I input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class UnresolvedTokenStreamOperative implements StreamOperative {
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
            c.input(String.class, "in").into((Class<? extends Tasklet<String, ?, String, ?, ?, ?>>)
                    (Class<?>) UnresolvedInputTasklet.class);
        }
    }

    public static final class ThrowingConstructorTasklet extends StatelessTestTasklet<String, Object, String> {
        static final String REFUSAL = "the author's own refusal";

        public ThrowingConstructorTasklet() throws IOException {
            throw new IOException(REFUSAL);
        }

        @Override
        public String name() {
            return "throwing_constructor";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class ThrowingTaskletStreamOperative implements StreamOperative {
        @Override
        public String factoryId() {
            return "throwing_tasklet";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "in")
                    .into(ClockAndKeyedValidationTest.NoExtractorTask.class)
                    .into(ThrowingConstructorTasklet.class);
        }
    }

    public static final class SingleTasklet extends StatelessTestTasklet<String, Object, String> {
        @Override
        public String name() {
            return "single_tasklet";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class SingleModeStreamOperative implements StreamOperative {
        @Override
        public String factoryId() {
            return "single_mode";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(SingleTasklet.class);
        }
    }

    public static final class StaticTasklet extends StatelessTestTasklet<String, Object, String> {
        @Override
        public String name() {
            return "static_tasklet";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public List<String> cellKeys() {
            return List.of("a", "b", "c", "d");
        }

        @Override
        public boolean cellsForOverridden() {
            return true;
        }

        @Override
        public List<String> cellsFor(String input) {
            return List.of(input.substring(0, 1));
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class StaticModeStreamOperative implements StreamOperative {
        @Override
        public String factoryId() {
            return "static_mode";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(StaticTasklet.class);
        }
    }

    public static final class BadEntryTypeStreamOperative implements StreamOperative {
        @Override
        public String factoryId() {
            return "bad_entry";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(Trade.class, "trade.raw").into(TradeTasklet.class);
        }
    }

    public static final class TradeTasklet extends StatelessTestTasklet<Trade, Object, String> {
        @Override
        public String name() {
            return "trade_tasklet";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(Trade input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class CandleTasklet extends StatelessTestTasklet<Candle, Object, String> {
        @Override
        public String name() {
            return "candle_tasklet";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(Candle input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class TradeProducerTasklet extends StatelessTestTasklet<String, Object, Trade> {
        @Override
        public String name() {
            return "trade_producer";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) throws TaskletException {}
    }

    public static final class TypeMismatchStreamOperative implements StreamOperative {
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

    /** Helper to keep the erasure-mismatch construction off the operative. */
    private static final class ChannelDeclMismatch {
        @SuppressWarnings({"unchecked", "rawtypes"})
        static void declareTradeIntoCandle(com.flezha.sdk.channel.Stream<Trade> stream) {
            ((com.flezha.sdk.channel.Stream) stream.named("trade.parsed")).into((Class) CandleTasklet.class);
        }
    }

    public static final class MissingCellsForOverriddenTasklet extends StatelessTestTasklet<String, Object, String> {
        @Override
        public String name() {
            return "missing_pfo_tasklet";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public List<String> cellKeys() {
            return List.of("a", "b");
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class MissingCellsForOverriddenStreamOperative implements StreamOperative {
        @Override
        public String factoryId() {
            return "missing_pfo_operative";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(MissingCellsForOverriddenTasklet.class);
        }
    }

    public static final class DynamicModeOnlyTasklet extends StatelessTestTasklet<String, Object, String> {
        @Override
        public String name() {
            return "dynamic_only_tasklet";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class DynamicModeOnlyStreamOperative implements StreamOperative {
        @Override
        public String factoryId() {
            return "dynamic_only_operative";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").keyBy(input -> input).into(DynamicModeOnlyTasklet.class);
        }
    }

    // ---- sdk-graph-authoring pins (mirror of the Rust operative_topology tests) ----

    @Nested
    class GraphAuthoring {

        @Test
        @DisplayName("branch() builds the identical topology to handle reuse")
        void branchBuildsIdenticalTopology() {
            var byHandles = StreamOperativeTopologyBuilder.build("g1", c -> {
                var validated = c.input(String.class, "in").into(SingleTasklet.class);
                validated.into(GraphStage1.class);
                validated.into(GraphStage2.class);
            });
            var byBranch = StreamOperativeTopologyBuilder.build("g2", c -> c.input(String.class, "in")
                    .into(SingleTasklet.class)
                    .branch(b -> b.into(GraphStage1.class), b -> b.into(GraphStage2.class)));
            assertThat(shape(byBranch)).isEqualTo(shape(byHandles));
        }

        @Test
        @DisplayName("branch() with fewer than two arms is refused")
        void branchWithOneArmRefused() {
            assertThatThrownBy(() -> StreamOperativeTopologyBuilder.build("g3", c -> c.input(String.class, "in")
                            .into(SingleTasklet.class)
                            .branch(b -> b.into(GraphStage1.class))))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageContaining("BranchTooFewArms")
                    .hasMessageContaining("branch(...) requires at least two arms; 1 given");
        }

        @Test
        @DisplayName("named nodes reuse one class at two positions; unnamed mentions unify into a fan-in")
        void namedNodesAndFanIn() {
            var t = StreamOperativeTopologyBuilder.build("g4", c -> {
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
            assertThatThrownBy(() -> StreamOperativeTopologyBuilder.build("g5", c -> {
                        var validated = c.input(String.class, "in").into(SingleTasklet.class);
                        validated.into(GraphStage2.class, "graph_stage1");
                        validated.into(GraphStage1.class);
                    }))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageContaining("NodeIdentityCollision")
                    .hasMessageContaining("graph_stage1");
        }

        @Test
        @DisplayName("channel names derive from the producer identity; .named overrides; collisions are refused")
        void derivedNamesAndCollision() {
            var t = StreamOperativeTopologyBuilder.build("g6", c -> c.input(String.class, "in")
                    .into(SingleTasklet.class)
                    .into(GraphStage1.class)
                    .named("orders.validated")
                    .into(GraphStage2.class));
            assertThat(t.nameToId()).containsKeys("single_tasklet", "orders.validated");

            assertThatThrownBy(() ->
                            StreamOperativeTopologyBuilder.build("g7", c -> c.input(String.class, "single_tasklet")
                                    .into(SingleTasklet.class)
                                    .into(GraphStage1.class)))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageContaining("DuplicateChannel")
                    .hasMessageContaining("single_tasklet");
        }

        @Test
        @DisplayName("a cycle is refused")
        void cycleRefused() {
            assertThatThrownBy(() -> StreamOperativeTopologyBuilder.build("g8", c -> c.input(String.class, "in")
                            .into(GraphStage1.class)
                            .into(GraphStage2.class)
                            .into(GraphStage1.class)))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageContaining("Cycle")
                    .hasMessageContaining("is on a cycle");
        }

        @Test
        @DisplayName("multiple entry channels build")
        void multipleEntryChannelsBuild() {
            // One operative may declare several entry channels — one per
            // external source (e.g. a market pipeline reading a trade feed and
            // an order-book feed). The controller routes each frame to its
            // entry by metadata["channel"]. Both entries and subgraphs resolve.
            var t = StreamOperativeTopologyBuilder.build("g9", c -> {
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
            var t = StreamOperativeTopologyBuilder.build(
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
            var t = StreamOperativeTopologyBuilder.build(
                    "g11", c -> c.input(String.class, "in").into(SingleTasklet.class));
            assertThat(t.channels().get(0).entryParsed()).isFalse();
        }

        @Test
        @DisplayName("a stream handle used after the freeze throws naming the operative")
        void handleAfterFreezeThrows() {
            var leaked = new java.util.concurrent.atomic.AtomicReference<com.flezha.sdk.channel.Stream<String>>();
            StreamOperativeTopologyBuilder.build(
                    "g12", c -> leaked.set(c.input(String.class, "in").into(SingleTasklet.class)));
            assertThatThrownBy(() -> leaked.get().into(GraphStage1.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("g12");
        }

        @Test
        @DisplayName("a sink with no consumers is the terminal; sealed() changes nothing")
        void sinkTerminalityIsDerivedFromTheWiring() {
            var terminal = StreamOperativeTopologyBuilder.build(
                    "g13",
                    c -> c.input(String.class, "in").into(SingleTasklet.class).sink("egress"));
            var sealed = StreamOperativeTopologyBuilder.build("g14", c -> c.input(String.class, "in")
                    .into(SingleTasklet.class)
                    .sink("egress")
                    .sealed());
            var hybrid = StreamOperativeTopologyBuilder.build("g15", c -> c.input(String.class, "in")
                    .into(SingleTasklet.class)
                    .sink("egress")
                    .into(GraphStage1.class));

            assertThat(shape(sealed))
                    .as("`.sealed()` must add no topology difference")
                    .isEqualTo(shape(terminal));
            assertThat(sinkShape(terminal)).isEqualTo(List.of(ChannelKind.PLUGIN_BOUND.name(), "0", "egress"));
            assertThat(sinkShape(hybrid)).isEqualTo(List.of(ChannelKind.INTER_TASKLET.name(), "1", "egress"));
        }

        @Test
        @DisplayName("the sink names the channel, and the no-argument form leaves it derived")
        void sinkNamesTheChannelAndTheNoArgumentFormDoesNot() {
            var named = StreamOperativeTopologyBuilder.build(
                    "g13a",
                    c -> c.input(String.class, "in").into(SingleTasklet.class).sink("items_output"));
            var derived = StreamOperativeTopologyBuilder.build(
                    "g13b",
                    c -> c.input(String.class, "in").into(SingleTasklet.class).sink());

            assertThat(named.channels().get(1).name()).isEqualTo("items_output");
            assertThat(derived.channels().get(1).name())
                    .as("sink() derives exactly as a channel with no sink does")
                    .isEqualTo("single_tasklet");
            assertThat(derived.channels().get(1).pluginSink()).isPresent();
        }

        @Test
        @DisplayName("sealing a leaf declares no channel")
        void sealingALeafDeclaresNoChannel() {
            // A leaf's output channel is created lazily, so this is the case
            // where "sealing changes no topology" is falsifiable: a sealed()
            // that reached the builder would conjure one.
            var sealed = StreamOperativeTopologyBuilder.build(
                    "g16",
                    c -> c.input(String.class, "in").into(SingleTasklet.class).sealed());
            var bare = StreamOperativeTopologyBuilder.build(
                    "g17", c -> c.input(String.class, "in").into(SingleTasklet.class));

            assertThat(shape(sealed)).isEqualTo(shape(bare));
        }

        /** Everything about the `egress` channel the sink declaration decides. */
        private java.util.List<String> sinkShape(StreamOperativeTopology t) {
            var channel = t.channels().stream()
                    .filter(ch -> ch.name().equals("egress"))
                    .findFirst()
                    .orElseThrow();
            return List.of(
                    channel.kind().name(),
                    String.valueOf(channel.consumerIds().size()),
                    channel.pluginSink().map(sink -> channel.name()).orElse("<none>"));
        }

        private java.util.List<String> shape(StreamOperativeTopology t) {
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
    static StreamOperativeConfigurationException catchConfigException(Runnable build) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(
                StreamOperativeConfigurationException.class, build::run);
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
        void correctionWindowRefusals() {
            StreamOperativeConfigurationException refused =
                    catchConfigException(() -> StreamOperativeTopologyBuilder.build("r1", c -> {
                        c.correctionWindow(0);
                        c.input(String.class, "in").into(SingleTasklet.class);
                    }));
            assertThat(refused.refusals())
                    .as("`correctionWindow(0)` is the one shape Java accepted before the core held the rules")
                    .contains("CorrectionWindowZero", "CorrectionWithoutTier", "CorrectionWithoutEventTime");
            assertThat(refused).hasMessageContaining("a zero window can correct nothing");
        }

        @Test
        @DisplayName("a zero retry budget is refused by name")
        void retryMaxZeroRefused() {
            assertThatThrownBy(() -> StreamOperativeTopologyBuilder.build(
                            "r2", c -> c.input(String.class, "in").retryMax(0).into(SingleTasklet.class)))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageContaining("RetryMaxZero")
                    .hasMessageContaining("channel `in` declared retry_max = 0");
        }

        @Test
        @DisplayName("a second sink on one channel is refused by name; the first still registered")
        void duplicatePluginSinkRefused() {
            assertThatThrownBy(() -> StreamOperativeTopologyBuilder.build("r3", c -> c.input(String.class, "in")
                            .into(SingleTasklet.class)
                            .sink("egress")
                            .sink()))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageContaining("DuplicatePluginSink")
                    .hasMessageContaining("channel `egress` was declared with `sink(...)` more than once");
        }

        @Test
        @DisplayName("a channel named by both named() and sink() is refused naming both")
        void channelNamedTwiceRefused() {
            assertThatThrownBy(() -> StreamOperativeTopologyBuilder.build("r3b", c -> c.input(String.class, "in")
                            .into(SingleTasklet.class)
                            .named("a")
                            .sink("b")))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageContaining("ChannelNamedTwice")
                    .hasMessageContaining(".named(`a`) and .sink(`b`) both name it");
        }

        @Test
        @DisplayName("two channels naming one sink is refused as a duplicate sink name")
        void duplicateSinkNameRefused() {
            assertThatThrownBy(() -> StreamOperativeTopologyBuilder.build("r3c", c -> {
                        var in = c.input(String.class, "in");
                        in.into(SingleTasklet.class).sink("shared");
                        in.into(GraphStage1.class).sink("shared");
                    }))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageContaining("DuplicateSinkName")
                    .hasMessageContaining("channel `shared` is named by two plugin sinks");
        }

        @Test
        @DisplayName("a plugin-bound channel that is not keyed is refused under the tier")
        void unkeyedChannelUnderTheTierRefused() {
            assertThatThrownBy(() -> StreamOperativeTopologyBuilder.build("r4", c -> {
                        c.effectivelyOnce();
                        c.input(String.class, "in").into(SingleTasklet.class).sink("egress");
                    }))
                    .isInstanceOf(StreamOperativeConfigurationException.class)
                    .hasMessageContaining("UnkeyedChannelUnderEffectivelyOnce")
                    .hasMessageContaining("must opt in with .keyed()");
        }
    }

    public static final class GraphStage1 extends StatelessTestTasklet<String, Object, String> {
        @Override
        public String name() {
            return "graph_stage1";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class GraphStage2 extends StatelessTestTasklet<String, Object, String> {
        @Override
        public String name() {
            return "graph_stage2";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }

    public static final class GraphMerge extends StatelessTestTasklet<String, Object, String> {
        @Override
        public String name() {
            return "graph_merge";
        }

        @Override
        protected Object initState(Optional<String> cellKey) {
            return new Object();
        }

        @Override
        public void run(String input, StateHandle<Object, NoChange> state, TaskletCtx ctx) {}
    }
}
