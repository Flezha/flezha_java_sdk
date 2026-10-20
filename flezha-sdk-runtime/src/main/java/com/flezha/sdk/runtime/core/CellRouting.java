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
import java.util.List;
import java.util.OptionalLong;
import javax.annotation.Nullable;

/**
 * Where a channel's payload goes. Two questions with one set of rules: which
 * values a Cell sees when an input is delivered to it ({@link #valuesForCell}),
 * and which values, keys and event time a payload carries before the core has
 * routed it at all ({@link #prepare}). Both decode for the channel's kind and
 * evaluate the consumer edge.
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
     * The values a Cell runs for one input: a prepared input's own values, each
     * decoded as an edge value; else the payload decoded and, when — and only
     * when — the core marks it untransformed, through the consumer edge's
     * transforms, keeping the values that route to this Cell. A flow producer
     * evaluated the edge when its activation completed, so applying it again
     * would compose the chain with itself.
     */
    List<Object> valuesForCell(Cell cell, WorkItem.Input in) {
        if (in.form() == WorkItem.Form.PREPARED) {
            return in.values().stream()
                    .map(bytes -> edgeCodec.deserialize(bytes, Object.class))
                    .toList();
        }
        ChannelDescriptor channel = channelOf(in.channel());
        Object base = decodePayload(channel, in.payload());
        ConsumerEdge edge = edgeInto(channel, cell.taskletId);
        if (edge == null) {
            return List.of(base);
        }
        List<Object> values =
                in.form() == WorkItem.Form.UNTRANSFORMED ? EdgeTransforms.valuesOf(edge, base) : List.of(base);
        return routedToCell(cell, edge, values);
    }

    /**
     * Of the edge's values, those that route to this Cell by key or by
     * {@code cellsFor}. The core routes a value by its one output key, so an
     * unkeyed edge output into a static consumer reaches every declared Cell
     * and only delivery can narrow it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Object> routedToCell(Cell cell, ConsumerEdge edge, List<Object> values) {
        if (edge.key().isPresent()) {
            return values.stream()
                    .filter(v -> cell.cellKey.equals(edge.keyOf(v)))
                    .toList();
        }
        if (topology.tasklet(cell.taskletId).cellKeys().isEmpty()) {
            return values;
        }
        Tasklet router = routers.get(cell.taskletId);
        return values.stream()
                .filter(v -> ((List<String>) router.cellsFor(v)).contains(cell.cellKey))
                .toList();
    }

    /**
     * The core's pre-routing question, asked per payload: which values does
     * each keyed consumer's edge yield and which Cells do they reach, and what
     * event time does its consumer read off it. A refusal is per payload — one
     * bad value does not fail the batch.
     */
    List<PrepareResult> prepare(ActivationKind.KeyRequest request) {
        ChannelDescriptor channel = topology.channel(request.channel());
        if (channel == null) {
            throw new IllegalStateException("key request names unknown channel id " + request.channel());
        }
        List<PrepareResult> results = new ArrayList<>(request.payloads().size());
        for (byte[] payload : request.payloads()) {
            try {
                results.add(new PrepareResult.Ok(prepared(request, channel, decodePayload(channel, payload))));
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

    /**
     * One decoded payload's answer: every value each keyed consumer's own edge
     * yields, with the Cells it names, and the event time the first timed
     * consumer reads off its first value. A payload its chains filter answers
     * no value, so the core mints no Cell for it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private PrepareAnswer prepared(ActivationKind.KeyRequest request, ChannelDescriptor channel, Object base) {
        List<PrepareAnswer.Value> values = new ArrayList<>();
        OptionalLong eventTime = OptionalLong.empty();
        for (ConsumerEdge edge : channel.consumers()) {
            TaskletDescriptor desc = topology.tasklet(edge.tasklet());
            if (desc == null) {
                continue;
            }
            boolean keyed = request.wantKeys() && (desc.dynamic() ? edge.key().isPresent() : desc.cellsForOverridden());
            boolean timed = request.wantEventTime() && eventTime.isEmpty() && desc.clock() == ClockKind.EVENT_TIME;
            if (!keyed && !timed) {
                continue;
            }
            Tasklet router = routers.get(edge.tasklet());
            List<Object> yielded = EdgeTransforms.valuesOf(edge, base);
            if (timed && !yielded.isEmpty()) {
                eventTime = OptionalLong.of(router.eventTime(yielded.get(0)));
            }
            for (Object value : keyed ? yielded : List.of()) {
                List<String> keys = desc.dynamic() ? List.of(edge.keyOf(value)) : router.cellsFor(value);
                values.add(new PrepareAnswer.Value(edge.tasklet(), keys, edgeCodec.serialize(value)));
            }
        }
        return new PrepareAnswer(values, eventTime);
    }
}
