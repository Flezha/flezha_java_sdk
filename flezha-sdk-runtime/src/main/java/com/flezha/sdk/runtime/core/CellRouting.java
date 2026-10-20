package com.flezha.sdk.runtime.core;

import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.runtime.relay.ChannelDescriptor;
import com.flezha.sdk.runtime.relay.ConsumerEdge;
import com.flezha.sdk.runtime.relay.RelayTopology;
import com.flezha.sdk.runtime.relay.TaskletDescriptor;
import com.flezha.sdk.runtime.state.ClockKind;
import com.flezha.sdk.runtime.state.KryoCodec;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import javax.annotation.Nullable;

/**
 * Where a channel's payload goes. Two questions with one set of rules: which
 * values a Cell actually sees when an input is delivered to it
 * ({@link #valuesForCell}), and which keys and which event time a payload
 * carries before the core has routed it at all ({@link #prepare}). Both decode
 * for the channel's kind and evaluate the consumer edge.
 *
 * <p>Nothing here outlives the call it is made in, which is why one instance
 * per relay is built at materialization and reused for every activation.
 */
final class CellRouting {

    private final RelayTopology topology;

    /** Per-Tasklet routing instances — {@code cellsFor} / {@code eventTime} only. */
    @SuppressWarnings("rawtypes")
    private final List<Tasklet> routers;

    private final KryoCodec edgeCodec;

    @SuppressWarnings("rawtypes")
    CellRouting(RelayTopology topology, List<Tasklet> routers, KryoCodec edgeCodec) {
        this.topology = topology;
        this.routers = routers;
        this.edgeCodec = edgeCodec;
    }

    /**
     * Decode an input payload and keep only the values that route to this
     * Cell, applying the consumer edge's transform chain when — and only
     * when — the core marks the payload untransformed. A flow producer
     * evaluated the edge when its activation completed, so applying it again
     * would compose the chain with itself.
     */
    List<Object> valuesForCell(Cell cell, WorkItem.Input in) {
        return routedToCell(cell, in.channel(), mappedValues(cell, in));
    }

    /**
     * The payload decoded and, when the core marks it untransformed, through
     * the consumer edge's transforms: every value the edge yields, before
     * keying decides which of them are this Cell's.
     */
    List<Object> mappedValues(Cell cell, WorkItem.Input in) {
        ChannelDescriptor channel = channelOf(in.channel());
        Object base = decodePayload(channel, in.payload());
        ConsumerEdge edge = edgeInto(channel, cell.taskletId);
        return edge != null && in.form() == WorkItem.Form.UNTRANSFORMED
                ? EdgeTransforms.valuesOf(edge, base)
                : List.of(base);
    }

    /** Of the edge's values, those that route to this Cell by key. */
    @SuppressWarnings("unchecked")
    List<Object> routedToCell(Cell cell, int channelId, List<Object> values) {
        ConsumerEdge edge = edgeInto(channelOf(channelId), cell.taskletId);
        if (edge == null || values.isEmpty()) {
            return values;
        }
        TaskletDescriptor desc = topology.tasklet(cell.taskletId);
        if (edge.key().isPresent()) {
            return values.stream()
                    .filter(v -> cell.cellKey.equals(edge.keyOf(v)))
                    .toList();
        }
        if (!desc.cellKeys().isEmpty()) {
            @SuppressWarnings("rawtypes")
            Tasklet router = routers.get(cell.taskletId);
            return values.stream()
                    .filter(v -> ((List<String>) router.cellsFor(v)).contains(cell.cellKey))
                    .toList();
        }
        return values;
    }

    /**
     * The core's pre-routing question, asked per payload: which Cell keys
     * does this payload reach, and what event time does its consumer read off
     * it. A refusal is per payload — one bad value does not fail the batch.
     */
    List<PrepareResult> prepare(ActivationKind.KeyRequest request) {
        ChannelDescriptor channel = topology.channel(request.channel());
        if (channel == null) {
            throw new IllegalStateException("key request names unknown channel id " + request.channel());
        }
        List<PrepareResult> results = new ArrayList<>(request.payloads().size());
        for (byte[] payload : request.payloads()) {
            try {
                Object base = decodePayload(channel, payload);
                List<String> keys = List.of();
                OptionalLong eventTime = OptionalLong.empty();
                if (request.wantKeys()) {
                    keys = prepareKeys(channel, base);
                }
                if (request.wantEventTime()) {
                    eventTime = prepareEventTime(channel, base);
                }
                results.add(new PrepareResult.Ok(new PrepareAnswer(keys, eventTime)));
            } catch (Throwable t) {
                results.add(
                        new PrepareResult.Err(new WorkError(t.toString(), t instanceof TaskletException.NonRetryable)));
            }
        }
        return results;
    }

    private ChannelDescriptor channelOf(int channelId) {
        ChannelDescriptor channel = topology.channel(channelId);
        if (channel == null) {
            throw new IllegalStateException("input names unknown channel id " + channelId);
        }
        return channel;
    }

    private Object decodePayload(ChannelDescriptor channel, byte[] payload) {
        if (channel.kind() == ChannelKind.ENTRY) {
            return channel.entryParser().map(parser -> parser.apply(payload)).orElse(payload);
        }
        return edgeCodec.deserialize(payload, Object.class);
    }

    @Nullable
    private static ConsumerEdge edgeInto(ChannelDescriptor channel, int taskletId) {
        for (ConsumerEdge edge : channel.consumers()) {
            if (edge.tasklet() == taskletId) {
                return edge;
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<String> prepareKeys(ChannelDescriptor channel, Object base) {
        for (ConsumerEdge edge : channel.consumers()) {
            TaskletDescriptor desc = topology.tasklet(edge.tasklet());
            if (desc == null) {
                continue;
            }
            if (desc.dynamic() && edge.key().isPresent()) {
                LinkedHashSet<String> keys = new LinkedHashSet<>();
                for (Object value : EdgeTransforms.valuesOf(edge, base)) {
                    keys.add(edge.keyOf(value));
                }
                return List.copyOf(keys);
            }
            if (!desc.dynamic() && desc.cellsForOverridden()) {
                LinkedHashSet<String> keys = new LinkedHashSet<>();
                Tasklet router = routers.get(edge.tasklet());
                for (Object value : EdgeTransforms.valuesOf(edge, base)) {
                    keys.addAll(router.cellsFor(value));
                }
                return List.copyOf(keys);
            }
        }
        return List.of();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private OptionalLong prepareEventTime(ChannelDescriptor channel, Object base) {
        for (ConsumerEdge edge : channel.consumers()) {
            TaskletDescriptor desc = topology.tasklet(edge.tasklet());
            if (desc == null || desc.clock() != ClockKind.EVENT_TIME) {
                continue;
            }
            List<Object> values = EdgeTransforms.valuesOf(edge, base);
            if (!values.isEmpty()) {
                Tasklet router = routers.get(edge.tasklet());
                return OptionalLong.of(router.eventTime(values.get(0)));
            }
        }
        return OptionalLong.empty();
    }
}
