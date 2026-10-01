package com.flezha.sdk.runtime.operator.builder;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.Embedding;
import com.flezha.sdk.channel.EntryChannel;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.operator.Operator;
import com.flezha.sdk.runtime.core.HostClassDeclaration;
import com.flezha.sdk.runtime.core.HostLink;
import com.flezha.sdk.runtime.operator.OperatorClassRegistry;
import com.flezha.sdk.runtime.operator.builder.OperatorTopologyBuilder.TaskletEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * An edge that ends in an operator class. A steps class becomes a node that runs one stepped
 * instance of it per key of the edge; a flow class is inlined, its {@code channels()} run on the
 * embedding builder with its entry fed by the edge and its nodes prefixed.
 */
final class OperatorEmbedding {

    private final OperatorTopologyBuilder owner;
    /** The flow classes being inlined, the innermost first. */
    private final Deque<Inline> inlining = new ArrayDeque<>();

    OperatorEmbedding(OperatorTopologyBuilder owner) {
        this.owner = owner;
    }

    /**
     * Close {@code channelId}'s chain on {@code operatorClass}. A steps class is registered with
     * the core here, never announced as a factory of its own, so the core can judge the embedding.
     */
    <O> Stream<O> wire(
            int channelId,
            Integer pendingChain,
            Class<?> payloadType,
            Class<? extends Operator> operatorClass,
            Class<O> output,
            Embedding embedding) {
        Operator operator = OperatorClassRegistry.probe(operatorClass);
        if (!(operator.run() instanceof Operator.Run.Steps)) {
            return new StreamImpl<>(owner, inline(channelId, pendingChain, payloadType, operator, embedding));
        }
        String factoryId = OperatorClassRegistry.registerClass(operatorClass);
        HostClassDeclaration.declareEmbedded(factoryId);
        int node = owner.registerNode(EmbeddingNode.class, embedding.name() != null ? embedding.name() : factoryId);
        TaskletEntry entry = owner.tasklet(node);
        if (entry.embeds != null && !entry.embeds.equals(factoryId)) {
            owner.refuse(FlezhaException.of("topology.node_identity_collision", Map.of("name", entry.name)));
        }
        entry.embeds = factoryId;
        entry.input = embedding.input();
        entry.maxCells = embedding.maxCells();
        entry.embeddedOutput = output;
        owner.closeChain(channelId, pendingChain, HostLink.into(node));
        return new StreamImpl<>(owner, node, null, output);
    }

    /**
     * Run a flow class's {@code channels()} on the builder. Answers its exit, the one node it
     * added that nothing consumes (its last when every node feeds another or a sink).
     */
    private int inline(
            int channelId, Integer pendingChain, Class<?> payloadType, Operator operator, Embedding embedding) {
        String factoryId = operator.factoryId();
        int first = owner.taskletCount();
        Inline frame = new Inline(
                factoryId,
                prefixed(embedding.name() != null ? embedding.name() : factoryId),
                channelId,
                pendingChain,
                payloadType);
        inlining.push(frame);
        try {
            operator.channels(owner);
        } finally {
            inlining.pop();
        }
        TreeSet<String> entries = new TreeSet<>(frame.entries);
        boolean chosen = entries.size() == 1 && (embedding.input() == null || entries.contains(embedding.input()));
        if (!chosen) {
            owner.refuse(FlezhaException.of(
                    "topology.embedded_entry_ambiguous",
                    Map.of("inner", factoryId, "channels", String.join(", ", entries))));
        }
        int added = owner.taskletCount();
        if (added == first) {
            throw FlezhaException.of(
                    "topology.invalid_setting",
                    Map.of(
                            "declaration", "the edge into '" + factoryId + "'",
                            "setting", "operator",
                            "value", "a class with no tasklet",
                            "expected", "a class with at least one tasklet"));
        }
        List<Integer> exits = new ArrayList<>();
        for (int id = first; id < added; id++) {
            if (owner.tasklet(id).outputChannelId == null) {
                exits.add(id);
            }
        }
        if (exits.size() > 1) {
            TreeSet<String> names = new TreeSet<>();
            exits.forEach(id -> names.add(owner.tasklet(id).name));
            owner.refuse(FlezhaException.of(
                    "topology.embedded_exit_ambiguous",
                    Map.of("inner", factoryId, "channels", String.join(", ", names))));
        }
        return exits.isEmpty() ? added - 1 : exits.get(0);
    }

    /** Whether a flow class is being inlined: an {@code input} then answers the feeding edge. */
    boolean inlining() {
        return !inlining.isEmpty();
    }

    /** The handle an {@code input} declared inside an inlined class answers: the feeding edge. */
    <T> EntryChannel<T> entry(Class<T> type, String name) {
        Inline frame = inlining.peek();
        frame.entries.add(name);
        if (!type.equals(frame.payloadType)) {
            owner.refuse(FlezhaException.of(
                    "topology.payload_type_mismatch",
                    Map.of(
                            "channel",
                            name,
                            "delivered",
                            OperatorTopologyBuilder.describe(frame.payloadType),
                            "tasklet",
                            frame.factoryId,
                            "input",
                            OperatorTopologyBuilder.describe(type))));
        }
        return new EntryChannelImpl<>(owner, frame.channel, name, frame.pending, type);
    }

    /** A node's identity inside the class being inlined: {@code <prefix>.<name>}. */
    String prefixed(String name) {
        Inline frame = inlining.peek();
        return frame == null ? name : frame.prefix + "." + name;
    }

    /** A flow class whose {@code channels()} runs on the builder, fed by one edge. */
    private static final class Inline {
        final String factoryId;
        final String prefix;
        final int channel;
        final Integer pending;
        final Class<?> payloadType;
        final List<String> entries = new ArrayList<>();

        Inline(String factoryId, String prefix, int channel, Integer pending, Class<?> payloadType) {
            this.factoryId = factoryId;
            this.prefix = prefix;
            this.channel = channel;
            this.pending = pending;
            this.payloadType = payloadType;
        }
    }
}
