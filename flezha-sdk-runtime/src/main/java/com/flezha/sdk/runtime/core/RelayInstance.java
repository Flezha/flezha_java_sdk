package com.flezha.sdk.runtime.core;

import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.internal.RuntimeBridge;
import com.flezha.sdk.runtime.relay.ChannelDescriptor;
import com.flezha.sdk.runtime.relay.RelayClassRegistry;
import com.flezha.sdk.runtime.relay.RelayTopology;
import com.flezha.sdk.runtime.relay.TaskletDescriptor;
import com.flezha.sdk.runtime.state.CellId;
import com.flezha.sdk.runtime.state.KryoCodec;
import com.flezha.sdk.runtime.state.PendingTimers;
import com.flezha.sdk.runtime.state.TimerOp;
import com.flezha.sdk.runtime.state.TimerPolicy;
import com.flezha.sdk.runtime.state.TimerSession;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * One live relay instance: the registered class resolved from the
 * activation's factory id, plus its per-Cell cells. Materialized on the
 * first activation naming an unknown relay id.
 *
 * <p>This is the mutable half of the host — the cell map and the user state
 * inside it. Its two collaborators read the topology and mutate nothing that
 * outlives a call: {@link CellRouting} answers where a payload goes,
 * {@link StateExchange} moves state across the boundary. Each is built once
 * here, never per activation.
 */
public final class RelayInstance {

    private final String relayId;
    private final String factoryId;

    final RelayTopology topology;

    /** Per-Tasklet routing instances — {@code cellsFor} / {@code eventTime} only. */
    @SuppressWarnings("rawtypes")
    final List<Tasklet> routers = new ArrayList<>();

    /** Per-Tasklet change/state codec (stable declared type ids). */
    private final List<KryoCodec> codecs = new ArrayList<>();

    private final List<TimerPolicy> timerPolicies = new ArrayList<>();

    /**
     * Codec for transient edge payloads crossing the core. Class-name
     * tagged (no declared id table) because a post-transform value can be
     * any user type; producer and consumer share this one instance.
     */
    final KryoCodec edgeCodec = new KryoCodec(List.of(), false);

    /** A class that runs in steps, whose stages the core folds into the driver's collect output instead of routing them. */
    final boolean steps;

    private final CellRouting routing;

    private final ConcurrentHashMap<CellAddress, Cell> cells = new ConcurrentHashMap<>();

    private record CellAddress(int tasklet, String cellKey) {}

    private RelayInstance(String relayId, String factoryId, RelayTopology topology) {
        this.relayId = relayId;
        this.factoryId = factoryId;
        this.topology = topology;
        this.steps = topology.steps();
        for (TaskletDescriptor desc : topology.tasklets()) {
            routers.add(instantiate(desc));
            codecs.add(desc.codec());
            timerPolicies.add(TimerPolicy.of(desc.model().maxTimersPerCell(), desc.name(), desc.clock()));
        }
        this.routing = new CellRouting(topology, routers, edgeCodec, steps);
    }

    static RelayInstance materialize(String relayId, String factoryId) {
        RelayTopology topology = RelayClassRegistry.getTopology(factoryId);
        if (topology != null) {
            return new RelayInstance(relayId, factoryId, topology);
        }
        throw new IllegalStateException("unknown factory `" + factoryId + "` for relay `" + relayId + "`");
    }

    @Nonnull
    public String factoryId() {
        return factoryId;
    }

    private static Tasklet<?, ?, ?> instantiate(TaskletDescriptor desc) {
        try {
            return RuntimeBridge.construct(desc.taskletClass());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("tasklet `" + desc.name() + "` has no public no-arg constructor: " + e, e);
        }
    }

    ActivationOutcome dispatch(Activation activation, ActivationDispatcher.Parts parts) {
        return switch (activation.kind()) {
            case ActivationKind.Work work -> dispatchWork(activation, work.items(), parts);
            case ActivationKind.RestoreFold fold -> restoreFold(activation, fold);
            case ActivationKind.SnapshotRequest ignored -> {
                Cell cell = cellFor(activation.target());
                yield new ActivationOutcome.Snapshot(cell.codec.serialize(cell.state));
            }
            case ActivationKind.LiveStateRead ignored -> new ActivationOutcome.StateJson(
                    StateExchange.stateJson(cellFor(activation.target())));
            case ActivationKind.MigrationSerialize ignored -> new ActivationOutcome.MigrationState(
                    StateExchange.stateJson(cellFor(activation.target())));
            case ActivationKind.ApplyState apply -> {
                StateExchange.applyState(cellFor(activation.target()), apply.stateJson());
                yield new ActivationOutcome.StateApplied();
            }
            case ActivationKind.KeyRequest request -> new ActivationOutcome.Prepared(routing.prepare(request));
            case ActivationKind.CompensateRun comp -> compensateRun(activation, comp.outputs(), parts);
        };
    }

    // ------------------------------------------------------------------
    // Work
    // ------------------------------------------------------------------

    private ActivationOutcome dispatchWork(
            Activation activation, List<WorkItem> items, ActivationDispatcher.Parts parts) {
        Cell cell = cellFor(activation.target());
        List<ItemEffect> effects = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            int item = index;
            ItemEffect effect = runItem(
                    activation, cell, items.get(index), payload -> parts.send(activation.token(), item, payload));
            effects.add(effect);
            // Stop at the first failed item; the core re-hands the tail.
            if (!(effect.verdict() instanceof Verdict.Ok)) {
                break;
            }
        }
        return new ActivationOutcome.Work(effects);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ItemEffect runItem(Activation activation, Cell cell, WorkItem item, Consumer<byte[]> parts) {
        long now = item instanceof WorkItem.Input in ? in.now() : ((WorkItem.Timer) item).scheduledAt();
        OptionalLong offset = item instanceof WorkItem.Input in ? in.consumedOffset() : OptionalLong.empty();
        boolean replay = item instanceof WorkItem.Input in && in.replay();

        EmitContext ctx =
                new EmitContext(this, cell, activation.token(), activation.changeSeq(), now, replay, offset.orElse(-1));
        TimerSession session = item instanceof WorkItem.Timer timer
                ? cell.timerPolicy.firingSession(new PendingTimers(), timer.scheduledAt(), timer.kind())
                : cell.timerPolicy.session(new PendingTimers(), now);
        ctx.timers = session;
        ctx.parts = parts;
        Tasklet tasklet = cell.tasklet;

        AtomicReference<List<TimerOp>> timerOps = new AtomicReference<>(List.of());
        // An entry payload is decoded and mapped before any user code runs, so
        // a message the entry cannot read is told apart from a tasklet failing
        // on one it could: the core parks the former and keeps the run going.
        List<Object> entryValues = null;
        Verdict verdict = null;
        try {
            entryValues = entryValues(cell, item);
        } catch (Throwable t) {
            verdict = new Verdict.Unreadable(t.toString());
        }
        if (verdict == null) {
            List<Object> mapped = entryValues;
            verdict = runUserCode(() -> {
                if (item instanceof WorkItem.Timer timer) {
                    tasklet.onTimer(timer.scheduledAt(), timer.kind(), ctx);
                } else {
                    WorkItem.Input in = (WorkItem.Input) item;
                    List<Object> values = mapped != null
                            ? routing.routedToCell(cell, in.channel(), mapped)
                            : routing.valuesForCell(cell, in.channel(), in.payload());
                    for (Object value : values) {
                        tasklet.run(value, ctx);
                    }
                }
                if (session != null) {
                    timerOps.set(session.commit(new PendingTimers()));
                }
            });
        }

        if (!(verdict instanceof Verdict.Ok)) {
            return new ItemEffect(
                    verdict, List.of(), List.of(), offset, now, List.of(), false, Optional.empty(), Optional.empty());
        }
        return new ItemEffect(
                verdict,
                cell.codec.serializeAll(ctx.changes),
                ctx.outputs,
                offset,
                now,
                timerOps.get(),
                ctx.checkpointed,
                Optional.empty(),
                ctx.completion);
    }

    /**
     * The values an input on an entry channel becomes through the entry
     * decoder and the entry edge's transforms, before keying, or {@code null}
     * for anything else, which decodes inside the invocation. Throws what the
     * decoder or a transform threw; the caller classifies that as unreadable.
     */
    private List<Object> entryValues(Cell cell, WorkItem item) {
        if (!(item instanceof WorkItem.Input in)) {
            return null;
        }
        ChannelDescriptor channel = topology.channel(in.channel());
        if (channel == null || channel.kind() != ChannelKind.ENTRY) {
            return null;
        }
        return routing.mappedValues(cell, in.channel(), in.payload());
    }

    /**
     * The ONE verdict authority for user code: run it and classify its
     * outcome. {@code NonRetryable} is the author's word; everything else —
     * including an unclassified throwable, panic parity with the Rust
     * host — stays retryable. An entry payload's decode is classified before
     * this, at {@link #entryValues}, as the one failure that is not the
     * tasklet's.
     */
    private static Verdict runUserCode(UserCode body) {
        try {
            body.invoke();
            return new Verdict.Ok();
        } catch (TaskletException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return e instanceof TaskletException.NonRetryable
                    ? new Verdict.NonRetryableError(message)
                    : new Verdict.RetryableError(message);
        } catch (Throwable t) {
            return new Verdict.RetryableError(t.toString());
        }
    }

    /** User code as the verdict authority runs it — may throw anything. */
    @FunctionalInterface
    private interface UserCode {
        void invoke() throws Exception;
    }

    /**
     * Compensation runs the same invocation machinery as an input item, over
     * the outputs a rolled-back stage produced.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ActivationOutcome compensateRun(
            Activation activation, List<byte[]> outputBytes, ActivationDispatcher.Parts parts) {
        Cell cell = cellFor(activation.target());
        List<Object> outputs = new ArrayList<>(outputBytes.size());
        for (byte[] bytes : outputBytes) {
            outputs.add(edgeCodec.deserialize(bytes, Object.class));
        }
        // Compensation is wall-anchored (at-least-once, idempotent by
        // author contract) and carries no timer session — structural, the
        // same refusal the pre-core adapter enforced.
        EmitContext ctx = new EmitContext(
                this, cell, activation.token(), activation.changeSeq(), System.currentTimeMillis(), false, -1);
        // A compensation runs no work item, so the core answers its part with `part.outside_invocation`.
        ctx.parts = payload -> parts.send(activation.token(), -1, payload);
        Tasklet tasklet = cell.tasklet;
        Verdict verdict = runUserCode(() -> tasklet.compensate(outputs, ctx));
        return new ActivationOutcome.Work(List.of(new ItemEffect(
                verdict,
                cell.codec.serializeAll(ctx.changes),
                ctx.outputs,
                OptionalLong.empty(),
                0,
                List.of(),
                false,
                Optional.empty(),
                ctx.completion)));
    }

    // ------------------------------------------------------------------
    // Cells
    // ------------------------------------------------------------------

    private ActivationOutcome restoreFold(Activation activation, ActivationKind.RestoreFold fold) {
        CellRef target = activation.target();
        Cell cell = freshCell(target.tasklet(), target.cellKey());
        StateExchange.foldInto(cell, fold);
        cells.put(new CellAddress(target.tasklet(), target.cellKey().orElse("")), cell);
        return new ActivationOutcome.Restored();
    }

    private Cell cellFor(CellRef target) {
        return cellFor(target.tasklet(), target.cellKey());
    }

    private Cell cellFor(int taskletId, Optional<String> cellKey) {
        return cells.computeIfAbsent(
                new CellAddress(taskletId, cellKey.orElse("")), k -> freshCell(taskletId, cellKey));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Cell freshCell(int taskletId, Optional<String> cellKey) {
        TaskletDescriptor desc = topology.tasklet(taskletId);
        if (desc == null) {
            throw new IllegalStateException("activation names unknown tasklet id " + taskletId);
        }
        // One Tasklet instance per Cell: a mutable field on a user's
        // Tasklet is per-Cell scratch, never shared across keys.
        Tasklet<?, ?, ?> tasklet = instantiate(desc);
        Object state = desc.model().initialState(tasklet, cellKey);
        return new Cell(
                taskletId,
                cellKey.orElse(""),
                tasklet,
                state,
                codecs.get(taskletId),
                timerPolicies.get(taskletId),
                CellId.of(relayId, desc.name(), cellKey));
    }
}
