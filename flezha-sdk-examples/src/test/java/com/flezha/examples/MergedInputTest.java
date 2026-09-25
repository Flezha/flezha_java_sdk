package com.flezha.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.flezha.examples.merged.ThermostatOperator;
import com.flezha.sdk.runtime.operator.UnboundedOperatorTopology;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An example that does not register teaches nothing, so prove the merge wires up. */
class MergedInputTest {

    @Test
    @DisplayName("both sources reach the one detector")
    void mergedTopologyRegisters() {
        UnboundedOperatorTopology topology = UnboundedOperatorTopologyBuilder.build(new ThermostatOperator());

        assertThat(topology.tasklets()).hasSize(1);
        int detector = topology.taskletNameToId().get("detector");
        assertThat(topology.channel(topology.lookupChannelId("readings")).consumerIds())
                .containsExactly(detector);
        assertThat(topology.channel(topology.lookupChannelId("config")).consumerIds())
                .containsExactly(detector);
    }
}
