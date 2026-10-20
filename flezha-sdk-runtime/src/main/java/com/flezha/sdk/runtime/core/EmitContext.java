package com.flezha.sdk.runtime.core;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.runtime.relay.ChannelDescriptor;
import com.flezha.sdk.runtime.relay.ConsumerEdge;
import com.flezha.sdk.runtime.relay.PluginSink;
import com.flezha.sdk.runtime.relay.TaskletDescriptor;
import com.flezha.sdk.runtime.state.ClockKind;
import com.flezha.sdk.runtime.state.TimerSession;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.EmitKeys;
import com.flezha.sdk.tasklet.StableId;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Per-invocation {@link Ctx}: holds the cell's state and the changes this
 * invocation records, serves the item's core-evaluated clock and the
 * replay-stable id, and evaluates emits producer-side into schema
 * {@link Output}s — edge transforms, keys and the consumer's event-time
 * extractor run here, so live delivery and replay cannot drift.
 */
final class EmitContext implements Ctx<Object, Object> {

    private final RelayInstance instance;
    private final Cell cell;
    private final long token;
    private final long invocationSeq;
    private final long now;
    private final boolean replay;
    private final long offset;

    final List<Output> outputs = new ArrayList<>();
    final List<Change<Object>> changes = new ArrayList<>();
    boolean checkpointed;
    Optional<byte[]> completion = Optional.empty();

    @Nullable
    TimerSession timers;

    /** The core's entries this invocation's parts and effects reach, under its work item. */
    CoreCalls core;

    int item;

    private byte[] identityBytes;
    private int stableIdOrdinal;

    EmitContext(
            RelayInstance instance, Cell cell, long token, long invocationSeq, long now, boolean replay, long offset) {
        this.instance = instance;
        this.cell = cell;
        this.token = token;
        this.invocationSeq = invocationSeq;
        this.now = now;
        this.replay = replay;
        this.offset = offset;
    }

    @Override
    public Object state() {
        return cell.state;
    }

    @Override
    public void change(@Nonnull Change<Object> change) {
        instance.topology.tasklet(cell.taskletId).model().record(change, cell.state, changes);
    }

    @Override
    public long now() {
        return now;
    }

    @Override
    @Nonnull
    public StableId stableId() {
        if (identityBytes == null) {
            identityBytes = cell.id.encodedBytes();
        }
        return StableId.derive(identityBytes, invocationSeq, stableIdOrdinal++);
    }

    @Override
    public void checkpoint() {
        checkpointed = true;
    }

    @Override
    public void complete(@Nonnull byte[] result) {
        completion = Optional.of(result.clone());
    }

    @Override
    public boolean replaying() {
        return replay;
    }

    @Override
    public long activationToken() {
        return token;
    }

    @Override
    public long inputOffset() {
        return offset;
    }

    @Override
    @Nonnull
    public Optional<String> cellKey() {
        // The cell stores a single-cell Tasklet's absent key as "";
        // that is not a key to emit under.
        return cell.cellKey.isEmpty() ? Optional.empty() : Optional.of(cell.cellKey);
    }

    private TimerSession session() {
        if (timers == null) {
            throw FlezhaException.of("runtime.timers_unavailable", Map.of());
        }
        return timers;
    }

    @Override
    public long timerAtEventTime(long scheduledAt) {
        return session().registerAtEventTime(scheduledAt);
    }

    @Override
    public long timerAfter(@Nonnull Duration delay) {
        return session().registerAfter(delay);
    }

    @Override
    public boolean cancelEventTimeTimer(long scheduledAt) {
        return session().cancel(TimerKind.EVENT_TIME, scheduledAt);
    }

    @Override
    public boolean cancelProcessingTimeTimer(long scheduledAt) {
        return session().cancel(TimerKind.PROCESSING_TIME, scheduledAt);
    }

    @Override
    public void emit(@Nonnull Object value) {
        doEmit(defaultOutputChannel(), value, null, null);
    }

    @Override
    public <C> void emit(@Nonnull String channelName, @Nonnull C value) {
        doEmit(resolveChannel(channelName), value, null, null);
    }

    @Override
    public void emitWithMeta(@Nonnull Object value, @Nonnull Map<String, String> meta) {
        doEmit(defaultOutputChannel(), value, meta, null);
    }

    @Override
    public <C> void emitWithMeta(@Nonnull String channelName, @Nonnull C value, @Nonnull Map<String, String> meta) {
        doEmit(resolveChannel(channelName), value, meta, null);
    }

    @Override
    public void emitKeyed(@Nonnull String key, @Nonnull Object value) {
        doEmit(defaultOutputChannel(), value, null, key);
    }

    @Override
    public void emitKeyedWithMeta(@Nonnull String key, @Nonnull Object value, @Nonnull Map<String, String> meta) {
        doEmit(defaultOutputChannel(), value, meta, key);
    }

    /**
     * Encoded as an emit of {@code value} on this tasklet's output channel would be. The core
     * refuses a channel that ends in no output, so a channel with no plugin end sends no bytes.
     */
    @Override
    public void part(@Nonnull Object value) {
        ChannelDescriptor descriptor = instance.topology.channel(defaultOutputChannel());
        byte[] bytes = descriptor == null || descriptor.pluginSink().isEmpty()
                ? new byte[0]
                : serializeOrThrow(descriptor.pluginSink().get().serializer(), value, descriptor.name());
        core.part(token, item, bytes);
    }

    @Override
    public <T> T effect(@Nonnull String name, @Nonnull Class<T> type, @Nonnull Supplier<T> fn) throws TaskletException {
        return core.effect(token, item, name, type, fn);
    }

    private int defaultOutputChannel() {
        TaskletDescriptor desc = instance.topology.tasklet(cell.taskletId);
        if (desc == null || desc.outputChannelId().isEmpty()) {
            throw FlezhaException.of(
                    "runtime.no_output_channel",
                    Map.of("tasklet", desc == null ? String.valueOf(cell.taskletId) : desc.name()));
        }
        return desc.outputChannelId().getAsInt();
    }

    private int resolveChannel(String name) {
        Integer id = instance.topology.lookupChannelId(name);
        if (id == null) {
            throw FlezhaException.of("runtime.unknown_channel", Map.of("channel", name));
        }
        return id;
    }

    /**
     * The single emit funnel: keyed-channel contract, reserved-metadata
     * refusal, then producer-side routing into schema outputs. Supplying no
     * key on a keyed channel resolves to this Cell's own key here, so the
     * defaulted emit and an explicit {@code emitKeyed} are one path from
     * this point on and their stamping cannot diverge.
     */
    private void doEmit(int channelId, Object value, @Nullable Map<String, String> meta, @Nullable String key) {
        ChannelDescriptor descriptor = instance.topology.channel(channelId);
        if (descriptor == null) {
            throw FlezhaException.of("runtime.unknown_channel", Map.of("channel", "<channel " + channelId + ">"));
        }
        if (descriptor.kind() == ChannelKind.ENTRY) {
            throw FlezhaException.of(
                    "runtime.channel_wrong_kind",
                    Map.of(
                            "relay",
                            instance.factoryId(),
                            "channel",
                            descriptor.name(),
                            "declared",
                            "entry",
                            "kind",
                            "output"));
        }
        key = EmitKeys.resolve(descriptor.name(), descriptor.keyed(), key, cellKey());
        if (meta != null) {
            for (String k : meta.keySet()) {
                if (k.startsWith(Ctx.RESERVED_METADATA_PREFIX)) {
                    throw FlezhaException.of("runtime.reserved_metadata_key", Map.of("key", k));
                }
            }
            if (descriptor.pluginSink().isEmpty()) {
                throw FlezhaException.of(
                        "runtime.metadata_on_inter_tasklet_channel", Map.of("channel", descriptor.name()));
            }
        }
        // Fan out to the consumer edges, then to the plugin output if the
        // channel has one. The plugin-bound terminal is the same path with
        // no consumer edges, which is why there is no second arm for it.
        collectEdgeOutputs(channelId, descriptor, value);
        if (descriptor.pluginSink().isEmpty()) {
            return;
        }
        PluginSink sink = descriptor.pluginSink().get();
        if (sink.call().isPresent()) {
            collectCallOutputs(descriptor, sink, value, meta);
            return;
        }
        collectPluginOutput(descriptor, serializeOrThrow(sink.serializer(), value, descriptor.name()), meta, key);
    }

    private static OptionalInt handoffProducer(ChannelDescriptor descriptor) {
        return descriptor.kind() == ChannelKind.INTER_TASKLET
                        && descriptor.pluginSink().isEmpty()
                ? descriptor.producer()
                : OptionalInt.empty();
    }

    /**
     * A call end's requests: the value through the call chain's transforms,
     * each result serialized and keyed by the chain as an edge's would be.
     * The core stamps them as calls when the activation settles.
     */
    private void collectCallOutputs(
            ChannelDescriptor descriptor, PluginSink sink, Object value, @Nullable Map<String, String> meta) {
        ConsumerEdge end = sink.call().get();
        for (Object request : EdgeTransforms.valuesOf(end, value)) {
            byte[] bytes = serializeOrThrow(sink.serializer(), request, descriptor.name());
            collectPluginOutput(descriptor, bytes, meta, end.keyOf(request));
        }
    }

    private static byte[] serializeOrThrow(Function<Object, byte[]> serializer, Object value, String channel) {
        try {
            return serializer.apply(value);
        } catch (RuntimeException | Error e) {
            throw FlezhaException.of(
                    "runtime.plugin_serializer_panicked", Map.of("channel", channel, "reason", e.toString()), e);
        }
    }

    /**
     * Producer-side edge evaluation: transform chain, edge key, consumer
     * event time. That is the flow shape — the core routes each output
     * on {@code (edge, key)} and reads the consumer.
     *
     * <p>A steps stage is held, not routed: the core keeps every output
     * as ONE released value on the instance's pass sheet and addresses each
     * consumer itself when the stage commits. One output per consumer would
     * therefore multiply the stage's released batch (and every downstream
     * node's work) by the channel's consumer count. So a structured
     * steps relay releases the value once, untransformed and unkeyed; each
     * consumer applies its own edge on delivery in
     * {@link CellRouting#valuesForCell}, which is the seam a live run and a
     * journal re-seed both cross.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void collectEdgeOutputs(int channelId, ChannelDescriptor descriptor, Object value) {
        if (instance.steps) {
            // The consumer the fold discards: the channel's first keeps the target a
            // well-formed Edge, and an inter-tasklet terminal names its producer, its
            // value the pass's handoff (a chained loop's carry). A plugin-bound
            // terminal releases nothing here.
            OptionalInt consumer = descriptor.consumers().isEmpty()
                    ? handoffProducer(descriptor)
                    : OptionalInt.of(descriptor.consumers().get(0).tasklet());
            if (consumer.isPresent()) {
                outputs.add(new Output(
                        new OutputTarget.Edge(channelId, consumer.getAsInt()),
                        Optional.empty(),
                        OptionalLong.empty(),
                        List.of(),
                        instance.edgeCodec.serialize(value)));
            }
            return;
        }
        for (ConsumerEdge edge : descriptor.consumers()) {
            List<Object> values = EdgeTransforms.valuesOf(edge, value);
            TaskletDescriptor consumer = instance.topology.tasklet(edge.tasklet());
            boolean eventTimeClocked = consumer != null && consumer.clock() == ClockKind.EVENT_TIME;
            for (Object v : values) {
                OptionalLong eventTime = OptionalLong.empty();
                if (eventTimeClocked) {
                    Tasklet router = instance.routers.get(edge.tasklet());
                    eventTime = OptionalLong.of(router.eventTime(v));
                }
                outputs.add(new Output(
                        new OutputTarget.Edge(channelId, edge.tasklet()),
                        Optional.ofNullable(edge.keyOf(v)),
                        eventTime,
                        List.of(),
                        instance.edgeCodec.serialize(v)));
            }
        }
    }

    /** A plugin output; the core stamps a keyed one's identity at settle. */
    private void collectPluginOutput(
            ChannelDescriptor descriptor, byte[] bytes, @Nullable Map<String, String> meta, @Nullable String key) {
        List<Map.Entry<String, String>> metadata = new ArrayList<>();
        if (meta != null) {
            for (Map.Entry<String, String> e : meta.entrySet()) {
                if (!"channel".equals(e.getKey())) {
                    metadata.add(Map.entry(e.getKey(), e.getValue()));
                }
            }
        }
        outputs.add(new Output(
                new OutputTarget.Plugin(descriptor.name()),
                Optional.ofNullable(key),
                OptionalLong.empty(),
                metadata,
                bytes));
    }
}
