package com.flezha.sdk.runtime.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.EntryChannel;
import com.flezha.sdk.channel.RoutedDocument;
import com.flezha.sdk.operator.UnboundedOperator;
import com.flezha.sdk.operator.UnboundedOperatorConfigurationException;
import com.flezha.sdk.runtime.operator.builder.LatestDocumentRouter;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code ChannelBuilder.routeLatest} declares on the topology. Every
 * guarantee the block carries rests on this shape, so it is asserted rather than
 * assumed: one single-cell node fed by both streams, the key function on the
 * traffic edge, and the routed pair keyed into a dynamic consumer.
 */
class RouteLatestExpansionTest {

    public record Thresholds(double limit) {}

    public record Trade(String symbol, double price) {}

    /**
     * Stands in for the per-key consumer the routed document is for — declared
     * exactly as an author declares one: its {@code Input} is the routed pair
     * over its own document type.
     */
    @Tasklet.Spec(name = "detector")
    public static final class Detector implements Tasklet<RoutedDocument<Thresholds>, Void, Void> {

        @Override
        public void run(RoutedDocument<Thresholds> input, Ctx<Void, Void> ctx) throws TaskletException {
            // The expansion is what is under test; the consumer only has to exist.
        }
    }

    /** The canonical shape: a document stream, a traffic stream, a keyed consumer. */
    static final class RoutedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "route_latest_expansion";
        }

        @Override
        public void channels(ChannelBuilder c) {
            EntryChannel<Thresholds> thresholds = c.input(Thresholds.class, "thresholds");
            EntryChannel<Trade> trades = c.input(Trade.class, "trades");
            c.routeLatest(Thresholds.class, thresholds, trades, Trade::symbol)
                    .keyBy(RoutedDocument::key)
                    .into(Detector.class);
        }
    }

    @Test
    @DisplayName("the block expands to one single-cell node fed by both streams")
    void expandsToOneSingleCellNode() {
        UnboundedOperatorTopology topology = UnboundedOperatorTopologyBuilder.build(new RoutedOperator());

        int routerId = topology.taskletNameToId().get(LatestDocumentRouter.NODE_NAME);
        TaskletDescriptor router = topology.tasklets().get(routerId);
        assertThat(router.cellKeys()).isEmpty();
        assertThat(router.dynamic()).isFalse();

        for (String source : new String[] {"thresholds", "trades"}) {
            ChannelDescriptor channel =
                    topology.channels().get(topology.nameToId().get(source));
            ConsumerEdge edge = channel.consumers().stream()
                    .filter(e -> e.tasklet() == routerId)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(source + " does not feed the router"));
            // The merge mapping (and, on traffic, the key function fused into it)
            // is one closure on the edge — no extra node, no extra hop.
            assertThat(edge.transform()).isPresent();
            assertThat(edge.key()).as("the router is single-cell").isEmpty();
        }

        // The routed pair carries its own key, which is what lets the consumer be
        // dynamically keyed off it.
        ChannelDescriptor out = topology.channels().get(router.outputChannelId().orElseThrow());
        ConsumerEdge detector = out.consumers().get(0);
        assertThat(topology.tasklets().get(detector.tasklet()).name()).isEqualTo("detector");
        assertThat(detector.key()).isPresent();
        assertThat(topology.tasklets().get(detector.tasklet()).dynamic()).isTrue();
    }

    @Test
    @DisplayName("the document's own class is registered with the router's state codec")
    void theDocumentTypeIsRegisteredWithTheStateCodec() {
        // The router retains the document in journaled state but is generic over
        // its type, so only the wiring can name it. Without this registration the
        // first snapshot of a router holding a typed document fails to serialize.
        UnboundedOperatorTopology topology = UnboundedOperatorTopologyBuilder.build(new RoutedOperator());
        int routerId = topology.taskletNameToId().get(LatestDocumentRouter.NODE_NAME);
        assertThat(topology.tasklets().get(routerId).kryoTypes())
                .containsAll(new LatestDocumentRouter().kryoTypes())
                .contains(Thresholds.class);
    }

    /** Two unnamed routers over one traffic stream: one node, one traffic edge. */
    static final class TwoUnnamedRoutersOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "route_latest_twice";
        }

        @Override
        public void channels(ChannelBuilder c) {
            EntryChannel<Thresholds> thresholds = c.input(Thresholds.class, "thresholds");
            EntryChannel<Thresholds> alt = c.input(Thresholds.class, "alt_thresholds");
            EntryChannel<Trade> trades = c.input(Trade.class, "trades");
            c.routeLatest(Thresholds.class, thresholds, trades, Trade::symbol);
            c.routeLatest(Thresholds.class, alt, trades, Trade::symbol);
        }
    }

    /** The same pair, named: two independent routers. */
    static final class TwoNamedRoutersOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "route_latest_twice_named";
        }

        @Override
        public void channels(ChannelBuilder c) {
            EntryChannel<Thresholds> thresholds = c.input(Thresholds.class, "thresholds");
            EntryChannel<Thresholds> alt = c.input(Thresholds.class, "alt_thresholds");
            EntryChannel<Trade> trades = c.input(Trade.class, "trades");
            // Leaves, unnamed: `.named(...)` would conjure the output
            // channel these routers never produce onto, which is an
            // inter-tasklet channel with no consumer.
            c.routeLatest(Thresholds.class, thresholds, trades, Trade::symbol, "primary");
            c.routeLatest(Thresholds.class, alt, trades, Trade::symbol, "secondary");
        }
    }

    @Test
    @DisplayName("a second router needs a node name")
    void aSecondRouterNeedsANodeName() {
        // fails when: two routers over one traffic stream silently collapse into
        // one node — they would share one retained document and one delivered
        // map, so each document stream would overwrite the other's.
        assertThatThrownBy(() -> UnboundedOperatorTopologyBuilder.build(new TwoUnnamedRoutersOperator()))
                .isInstanceOf(UnboundedOperatorConfigurationException.class)
                .hasMessageContaining("trades")
                .hasMessageContaining(LatestDocumentRouter.NODE_NAME);

        UnboundedOperatorTopology topology = UnboundedOperatorTopologyBuilder.build(new TwoNamedRoutersOperator());
        assertThat(topology.taskletNameToId()).containsKeys("primary", "secondary");
        assertThat(topology.taskletNameToId()).doesNotContainKey(LatestDocumentRouter.NODE_NAME);
    }

    /** One channel as both streams: the document stream is the traffic stream. */
    static final class SelfRoutedOperator implements UnboundedOperator {
        @Override
        public String factoryId() {
            return "route_latest_self";
        }

        @Override
        public void channels(ChannelBuilder c) {
            EntryChannel<Trade> trades = c.input(Trade.class, "trades");
            c.routeLatest(Trade.class, trades, trades, Trade::symbol);
        }
    }

    @Test
    @DisplayName("one channel cannot be both streams")
    void oneChannelCannotBeBothStreams() {
        // fails when: the router would retain traffic items as documents and
        // deliver them back to their own keys.
        assertThatThrownBy(() -> UnboundedOperatorTopologyBuilder.build(new SelfRoutedOperator()))
                .isInstanceOf(UnboundedOperatorConfigurationException.class)
                .hasMessageContaining("trades");
    }
}
