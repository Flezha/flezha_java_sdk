package com.flezha.sdk.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.sdk.channel.CallTarget;
import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.channel.Response;
import com.flezha.sdk.runtime.relay.RelayTopologyBuilderTest.SingleTasklet;
import com.flezha.sdk.runtime.relay.builder.RelayTopologyBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code call(...)}: the link the builder records, the Response channel it declares, and the call
 * end the materialized channel carries.
 */
class CallEndTest {

    @Test
    @DisplayName("a call closes its chain with the call link and declares <channel>.response as an entry")
    void aCallClosesItsChainAndDeclaresTheResponseChannel() {
        RelayTopology topology = RelayTopologyBuilder.build("calls", c -> c.input(String.class, "in")
                .into(SingleTasklet.class)
                .named("ask")
                .keyBy(value -> value)
                .call(CallTarget.factory("agent").create(false).channel("turn"), String.class, 60_000)
                .map(String.class, Response::request)
                .into(SingleTasklet.class));

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("link", "call");
        call.put("factory", "agent");
        call.put("deadline_ms", 60_000L);
        call.put("create", false);
        call.put("channel", "turn");
        call.put("response", "ask.response");
        assertThat(chains(topology, 1)).contains(List.of(Map.of("link", "key"), call));

        ChannelDescriptor ask = topology.channels().get(1);
        assertThat(ask.kind()).isEqualTo(ChannelKind.PLUGIN_BOUND);
        assertThat(ask.pluginSink()).hasValueSatisfying(sink -> assertThat(sink.call())
                .hasValueSatisfying(end -> assertThat(end.key()).isPresent()));
        ChannelDescriptor response = topology.channels().get(2);
        assertThat(response.name()).isEqualTo("ask.response");
        assertThat(response.kind()).isEqualTo(ChannelKind.ENTRY);
        assertThat(response.payloadType()).isEqualTo(Response.class);
        assertThat(response.consumerIds())
                .as("back into the tasklet that made the call")
                .containsExactly(0);
    }

    @Test
    @DisplayName("a call to a named instance records its target, create true and no channel")
    void aCallToANamedInstanceRecordsItsTarget() {
        RelayTopology topology = RelayTopologyBuilder.build("calls_pricing", c -> c.input(String.class, "in")
                .into(SingleTasklet.class)
                .call(CallTarget.id("pricing"), String.class, 500)
                .map(String.class, Response::request)
                .into(SingleTasklet.class));

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("link", "call");
        call.put("target", "pricing");
        call.put("deadline_ms", 500L);
        call.put("create", true);
        call.put("response", "single_tasklet.response");
        assertThat(chains(topology, 1)).containsExactly(List.of(call));
        assertThat(topology.channels().get(1).pluginSink()).hasValueSatisfying(sink -> assertThat(sink.call())
                .hasValueSatisfying(end -> assertThat(end.key()).isEmpty()));
    }

    /** Every chain declared on one channel, as the record spells it. */
    @SuppressWarnings("unchecked")
    private static List<List<Object>> chains(RelayTopology t, int channel) {
        var channels = (List<Map<String, Object>>) t.declaration().get("channels");
        return (List<List<Object>>) channels.get(channel).get("chains");
    }
}
