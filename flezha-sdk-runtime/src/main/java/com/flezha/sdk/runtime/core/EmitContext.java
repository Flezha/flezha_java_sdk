package com.flezha.sdk.runtime.core;

import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.runtime.operative.ChannelDescriptor;
import com.flezha.sdk.runtime.operative.ConsumerEdge;
import com.flezha.sdk.runtime.operative.TaskletDescriptor;
import com.flezha.sdk.runtime.state.TimerSession;
import com.flezha.sdk.state.EventTimeFrom;
import com.flezha.sdk.state.TimerKind;
import com.flezha.sdk.tasklet.EmitKeys;
import com.flezha.sdk.tasklet.StableId;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletCtx;
import com.flezha.sdk.tasklet.TaskletException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Per-invocation {@link TaskletCtx}: serves the item's core-evaluated
 * clock, the replay-stable id, and evaluates emits producer-side into
 * schema {@link Output}s — edge transforms, keys and the consumer's
 * event-time extractor run here, so live delivery and replay cannot drift.
 */
final class EmitContext implements TaskletCtx {

    private final OperativeInstance instance;
    private final Cell cell;
    private final long token;
    private final long invocationSeq;
    private final long now;
    private final boolean replay;

    final List<Output> outputs = new ArrayList<>();
    boolean checkpointed;
    Optional<byte[]> completion = Optional.empty();

    @Nullable
    TimerSession timers;

    private byte[] identityBytes;
    private int stableIdOrdinal;
    private int emitOrdinal;

    EmitContext(OperativeInstance instance, Cell cell, long token, long invocationSeq, long now, boolean replay) {
        this.instance = instance;
        this.cell = cell;
        this.token = token;
        this.invocationSeq = invocationSeq;
        this.now = now;
        this.replay = replay;
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
    @Nonnull
    public Optional<String> cellKey() {
        // The cell stores a single-cell Tasklet's absent key as "";
        // that is not a key to emit under.
        return cell.cellKey.isEmpty() ? Optional.empty() : Optional.of(cell.cellKey);
    }

    private TimerSession session() throws TaskletException {
        if (timers == null) {
            throw new TaskletException.TimersUnavailable();
        }
        return timers;
    }

    @Override
    public long timerAtEventTime(long scheduledAt) throws TaskletException {
        return session().registerAtEventTime(scheduledAt);
    }

    @Override
    public long timerAfter(@Nonnull Duration delay) throws TaskletException {
        return session().registerAfter(delay);
    }

    @Override
    public boolean cancelEventTimeTimer(long scheduledAt) throws TaskletException {
        return session().cancel(TimerKind.EVENT_TIME, scheduledAt);
    }

    @Override
    public boolean cancelProcessingTimeTimer(long scheduledAt) throws TaskletException {
        return session().cancel(TimerKind.PROCESSING_TIME, scheduledAt);
    }

    @Override
    public <C> void emit(@Nonnull C value) throws TaskletException {
        doEmit(defaultOutputChannel(), value, null, null);
    }

    @Override
    public <C> void emit(@Nonnull String channelName, @Nonnull C value) throws TaskletException {
        doEmit(resolveChannel(channelName), value, null, null);
    }

    @Override
    public <C> void emitWithMeta(@Nonnull C value, @Nonnull Map<String, String> meta) throws TaskletException {
        doEmit(defaultOutputChannel(), value, meta, null);
    }

    @Override
    public <C> void emitWithMeta(@Nonnull String channelName, @Nonnull C value, @Nonnull Map<String, String> meta)
            throws TaskletException {
        doEmit(resolveChannel(channelName), value, meta, null);
    }

    @Override
    public <C> void emitKeyed(@Nonnull String key, @Nonnull C value) throws TaskletException {
        doEmit(defaultOutputChannel(), value, null, key);
    }

    @Override
    public <C> void emitKeyedWithMeta(@Nonnull String key, @Nonnull C value, @Nonnull Map<String, String> meta)
            throws TaskletException {
        doEmit(defaultOutputChannel(), value, meta, key);
    }

    private int defaultOutputChannel() throws TaskletException {
        TaskletDescriptor desc = instance.topology.tasklet(cell.taskletId);
        if (desc == null || desc.outputChannelId().isEmpty()) {
            throw new TaskletException.UnknownChannel("<no output channel declared on this tasklet>");
        }
        return desc.outputChannelId().getAsInt();
    }

    private int resolveChannel(String name) throws TaskletException {
        Integer id = instance.topology.lookupChannelId(name);
        if (id == null) {
            throw new TaskletException.UnknownChannel(name);
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
    private void doEmit(int channelId, Object value, @Nullable Map<String, String> meta, @Nullable String key)
            throws TaskletException {
        ChannelDescriptor descriptor = instance.topology.channel(channelId);
        if (descriptor == null) {
            throw new TaskletException.UnknownChannel("<channel " + channelId + ">");
        }
        if (descriptor.kind() == ChannelKind.ENTRY) {
            throw new TaskletException("cannot emit on entry channel `" + descriptor.name() + "` from a tasklet");
        }
        key = EmitKeys.resolve(descriptor.name(), descriptor.keyed(), key, cellKey());
        if (meta != null) {
            for (String k : meta.keySet()) {
                if (k.startsWith(TaskletCtx.RESERVED_METADATA_PREFIX)) {
                    throw new TaskletException.ReservedMetadataKey(k);
                }
            }
            if (descriptor.pluginSink().isEmpty()) {
                throw new TaskletException.MetadataNotAllowedOnInterTaskletChannel(descriptor.name());
            }
        }
        // Fan out to the consumer edges, then to the plugin sink if the
        // channel has one. The plugin-bound terminal is the same path with
        // no consumer edges, which is why there is no second arm for it.
        collectEdgeOutputs(channelId, descriptor, value);
        if (descriptor.pluginSink().isPresent()) {
            byte[] bytes = serializeOrThrow(descriptor.pluginSink().get().serializer(), value, descriptor.name());
            collectPluginOutput(descriptor, bytes, meta, key);
        }
    }

    private static byte[] serializeOrThrow(Function<Object, byte[]> serializer, Object value, String channel)
            throws TaskletException {
        try {
            return serializer.apply(value);
        } catch (RuntimeException | Error e) {
            throw new TaskletException.PluginSerializerFailure(channel, e.toString(), e);
        }
    }

    /**
     * Producer-side edge evaluation: transform chain, edge key, consumer
     * event time. That is the STREAM shape — the core routes each output
     * on {@code (edge, key)} and reads the consumer.
     *
     * <p>A workflow stage is folded, not routed: {@code fold_effect}
     * ({@code flz_core::host::workflow}) discards the consumer and collects
     * every output as ONE released value, which the driver then fans out to
     * each child itself. One output per consumer would therefore multiply
     * the stage's released batch — and every downstream node's work — by
     * the channel's consumer count. So a structured workflow releases the
     * value once, untransformed and unkeyed; each consumer applies its own
     * edge on delivery in {@link CellRouting#valuesForCell}, which is the
     * seam a live run and a journal re-seed both cross. Mirrors the Rust
     * SDK's {@code WorkflowCollectAdapter::emit}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void collectEdgeOutputs(int channelId, ChannelDescriptor descriptor, Object value) {
        // A channel with no consumer releases nothing: on a workflow that
        // is a plugin-bound terminal, and seeding the next stage from it
        // would hand the driver a terminal output.
        if (descriptor.consumers().isEmpty()) {
            return;
        }
        if (instance.workflow) {
            outputs.add(new Output(
                    // The consumer the fold discards; naming the channel's
                    // first keeps the target a well-formed Edge.
                    new OutputTarget.Edge(
                            channelId, descriptor.consumers().get(0).tasklet()),
                    Optional.empty(),
                    OptionalLong.empty(),
                    List.of(),
                    instance.edgeCodec.serialize(value)));
            return;
        }
        for (ConsumerEdge edge : descriptor.consumers()) {
            List<Object> values = EdgeTransforms.valuesOf(edge, value);
            TaskletDescriptor consumer = instance.topology.tasklet(edge.tasklet());
            boolean eventTimeClocked = consumer != null && consumer.clockType() == EventTimeFrom.class;
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

    /**
     * A plugin output. Non-tier keyed channels carry the identity stamps
     * the pre-core frame writer minted; under the tier the controller
     * derives them at flush time, so none ride here — both byte-shapes
     * preserved.
     */
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
        if (descriptor.keyed() && !instance.topology.effectivelyOnce()) {
            metadata.add(Map.entry("flz.cell", cell.id.encoded()));
            metadata.add(Map.entry("flz.seq", invocationSeq + "." + emitOrdinal));
            metadata.add(Map.entry("flz.key", key));
        }
        emitOrdinal++;
        outputs.add(new Output(
                new OutputTarget.Plugin(descriptor.name()),
                Optional.ofNullable(key),
                OptionalLong.empty(),
                metadata,
                bytes));
    }
}
