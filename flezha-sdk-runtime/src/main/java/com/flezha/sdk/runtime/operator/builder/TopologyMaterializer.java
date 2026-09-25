package com.flezha.sdk.runtime.operator.builder;

import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.channel.RetryPolicy;
import com.flezha.sdk.runtime.core.DerivedDeclaration;
import com.flezha.sdk.runtime.operator.ChannelDescriptor;
import com.flezha.sdk.runtime.operator.ConsumerEdge;
import com.flezha.sdk.runtime.operator.PluginSink;
import com.flezha.sdk.runtime.operator.TaskletDescriptor;
import com.flezha.sdk.runtime.operator.UnboundedOperatorTopology;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder.ChannelEntry;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder.PendingEdge;
import com.flezha.sdk.runtime.operator.builder.UnboundedOperatorTopologyBuilder.TaskletEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Turns the DERIVED declaration the core answered, plus the closures the
 * builder held aside, into the frozen descriptors a
 * {@link UnboundedOperatorTopology} is made of. The channel names, kinds and
 * consumer edges are the core's answer, never the builder's claim; this class
 * decides nothing and refuses nothing.
 */
final class TopologyMaterializer {

    private TopologyMaterializer() {}

    static UnboundedOperatorTopology materialize(
            String factoryId,
            List<ChannelEntry> channels,
            List<TaskletEntry> tasklets,
            int[] capacities,
            Map<String, Object> declaration,
            DerivedDeclaration derived) {
        List<ChannelDescriptor> descriptors = channelDescriptors(channels, derived);
        Map<String, Integer> nameToId = new LinkedHashMap<>();
        Map<String, Integer> taskletNameToId = new LinkedHashMap<>();
        for (int id = 0; id < descriptors.size(); id++) {
            nameToId.put(descriptors.get(id).name(), id);
        }
        for (int id = 0; id < tasklets.size(); id++) {
            taskletNameToId.put(tasklets.get(id).name, id);
        }
        return new UnboundedOperatorTopology(
                factoryId,
                descriptors,
                nameToId,
                taskletDescriptors(tasklets, descriptors, capacities),
                taskletNameToId,
                Boolean.TRUE.equals(declaration.get("effectively_once")),
                declaration);
    }

    private static List<ChannelDescriptor> channelDescriptors(List<ChannelEntry> channels, DerivedDeclaration derived) {
        List<ChannelDescriptor> descriptors = new ArrayList<>(channels.size());
        for (int index = 0; index < channels.size(); index++) {
            ChannelEntry entry = channels.get(index);
            DerivedDeclaration.Channel answer = derived.channel(index);
            Optional<PluginSink> pluginSink =
                    answer.sink() == null ? Optional.empty() : Optional.of(new PluginSink(entry.pluginSinkSerializer));
            descriptors.add(new ChannelDescriptor(
                    answer.name(),
                    kindOf(answer.kind()),
                    answer.producer() == null ? OptionalInt.empty() : OptionalInt.of(answer.producer()),
                    edges(entry, answer),
                    entry.payloadType,
                    Optional.ofNullable(entry.entryParser),
                    entry.entryParsed,
                    pluginSink,
                    entry.overflowPolicy,
                    new RetryPolicy(entry.retryMaxAttempts, entry.retryBackoffBaseMs),
                    entry.keyed));
        }
        return descriptors;
    }

    /**
     * One edge per consumer the core derived, carrying the closures the
     * builder held aside for the chain the core says produced it. Which chain
     * survives a re-declaration is the core's answer; the index is the only
     * thing read here.
     */
    private static List<ConsumerEdge> edges(ChannelEntry entry, DerivedDeclaration.Channel answer) {
        List<ConsumerEdge> out = new ArrayList<>(answer.consumers().size());
        for (DerivedDeclaration.Edge edge : answer.consumers()) {
            PendingEdge chain = entry.chains.get(edge.chain());
            out.add(new ConsumerEdge(
                    edge.tasklet(), Optional.ofNullable(chain.transform()), Optional.ofNullable(chain.key())));
        }
        return out;
    }

    private static ChannelKind kindOf(String derived) {
        return switch (derived) {
            case "entry" -> ChannelKind.ENTRY;
            case "plugin" -> ChannelKind.PLUGIN_BOUND;
            default -> ChannelKind.INTER_TASKLET;
        };
    }

    private static List<TaskletDescriptor> taskletDescriptors(
            List<TaskletEntry> tasklets, List<ChannelDescriptor> channels, int[] capacities) {
        List<TaskletDescriptor> descriptors = new ArrayList<>(tasklets.size());
        for (int i = 0; i < tasklets.size(); i++) {
            TaskletEntry entry = tasklets.get(i);
            descriptors.add(new TaskletDescriptor(
                    entry.name,
                    entry.taskletClass,
                    entry.cellKeys,
                    entry.cellsForOverridden,
                    entry.dynamic,
                    inputChannel(channels, i),
                    entry.outputChannelId == null ? OptionalInt.empty() : OptionalInt.of(entry.outputChannelId),
                    entry.inputType,
                    entry.outputType,
                    entry.changeType,
                    entry.clockType,
                    entry.configType,
                    entry.kryoTypes,
                    entry.schemaVersion,
                    entry.minReadableSchemaVersion,
                    entry.compactAfterBytes,
                    capacities[i],
                    entry.compensateOverridden,
                    entry.cellIdleTtl,
                    entry.maxCells));
        }
        return descriptors;
    }

    /**
     * A fan-in tasklet keeps its lowest-id input channel here; every
     * incoming channel stays reachable via the channel descriptors, and the
     * dispatcher routes via the channel-side mapping.
     */
    private static OptionalInt inputChannel(List<ChannelDescriptor> channels, int tasklet) {
        for (int id = 0; id < channels.size(); id++) {
            if (channels.get(id).consumerIds().contains(tasklet)) {
                return OptionalInt.of(id);
            }
        }
        return OptionalInt.empty();
    }
}
