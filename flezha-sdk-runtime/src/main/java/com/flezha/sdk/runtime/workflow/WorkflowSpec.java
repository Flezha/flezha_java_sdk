package com.flezha.sdk.runtime.workflow;

import com.flezha.sdk.channel.ChannelKind;
import com.flezha.sdk.channel.RetryPolicy;
import com.flezha.sdk.internal.RuntimeBridge;
import com.flezha.sdk.operative.LoopMode;
import com.flezha.sdk.operative.RollbackMode;
import com.flezha.sdk.operative.StreamOperativeConfigurationException;
import com.flezha.sdk.operative.WorkflowOperative;
import com.flezha.sdk.operative.WorkflowOperativeConfigurationException;
import com.flezha.sdk.runtime.operative.ChannelDescriptor;
import com.flezha.sdk.runtime.operative.StreamOperativeTopology;
import com.flezha.sdk.runtime.operative.TaskletDescriptor;
import com.flezha.sdk.runtime.operative.builder.GraphShape;
import com.flezha.sdk.runtime.operative.builder.StreamOperativeTopologyBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Frozen per-class authoring result for a {@link WorkflowOperative}, produced and
 * validated once at registration and reused for every spawn of the class.
 * Mirrors the Rust SDK's {@code WorkflowSpec}: the declared {@link #topology}
 * and its ordered {@link #chain}.
 */
public final class WorkflowSpec {

    private final String factoryId;
    private final StreamOperativeTopology topology;
    private final List<Integer> chain;
    private final Optional<Duration> maxDuration;
    private final RollbackMode rollbackMode;
    private final boolean persistenceEnabled;
    private final boolean syncDurability;
    private final LoopMode loopMode;
    private final RetryPolicy retry;
    private final int barrierRetryAttempts;
    private final Optional<Duration> wakeAfter;

    private WorkflowSpec(WorkflowOperative probe, StreamOperativeTopology topology, List<Integer> chain) {
        this.factoryId = probe.factoryId();
        this.topology = topology;
        this.chain = List.copyOf(chain);
        this.loopMode = probe.loopMode();
        this.wakeAfter = probe.wakeAfter();
        this.maxDuration = probe.maxDuration();
        this.rollbackMode = probe.rollbackMode();
        this.persistenceEnabled = probe.persistenceEnabled();
        this.syncDurability = probe.syncDurability();
        this.retry = resolveRetry(this.factoryId, probe);
        this.barrierRetryAttempts = Math.max(1, probe.barrierRetryAttempts());
    }

    @Nonnull
    public String factoryId() {
        return factoryId;
    }

    @Nonnull
    public StreamOperativeTopology topology() {
        return topology;
    }

    /** Tasklets in execution order (entry consumer → … → terminal). */
    @Nonnull
    public List<Integer> chain() {
        return chain;
    }

    @Nonnull
    public Optional<Duration> maxDuration() {
        return maxDuration;
    }

    /** Saga rollback scope applied on abnormal termination. */
    @Nonnull
    public RollbackMode rollbackMode() {
        return rollbackMode;
    }

    /**
     * The failing-cell retry ladder. Resolved onto the SAME
     * {@link RetryPolicy} the stream dead-letter ladder uses, so both share one
     * backoff implementation.
     */
    @Nonnull
    public RetryPolicy retry() {
        return retry;
    }

    /** Sends of one commit barrier before an unacked commit fails the run (at least 1). */
    public int barrierRetryAttempts() {
        return barrierRetryAttempts;
    }

    /** How many times this class runs its chain. */
    @Nonnull
    public LoopMode loopMode() {
        return loopMode;
    }

    /** How long a parked instance sleeps before the controller wakes it. */
    @Nonnull
    public Optional<Duration> wakeAfter() {
        return wakeAfter;
    }

    /** Class-level persistence toggle; a structured workflow's Changes are flushed to the WAL. */
    public boolean persistenceEnabled() {
        return persistenceEnabled;
    }

    /**
     * Synchronous remote durability: the controller ships each commit barrier
     * to its durability backend before acknowledging it (RPO 0). Inert unless
     * the controller has a backend configured.
     */
    public boolean syncDurability() {
        return syncDurability;
    }

    /**
     * Build and validate the spec for a workflow class: its
     * {@link WorkflowOperative#tasklets} declaration, validated as a single
     * entry-rooted DAG. Nothing about the handoffs is judged here: the journal
     * codec is the core's to derive, and the one rule a durable CHAINED loop
     * still owes (a terminal tasklet releasing an inter-tasklet channel,
     * the only kind the journal carries) is refused once in `flz_core`'s
     * `register_structured_workflow`, which every host reaches through
     * `declareClass`. Restating it here would be a second implementation of a
     * core rule, which is how `BranchingChainedLoop` is already handled.
     *
     * @throws WorkflowOperativeConfigurationException on invalid authoring
     *     (external-stop-only, no tasklet, branching, cycle, dynamic cell
     *     mode, disconnected tasklet, or no entry channel).
     */
    @Nonnull
    public static WorkflowSpec build(@Nonnull Class<? extends WorkflowOperative> operativeClass) {
        WorkflowOperative probe = resolveProbe(operativeClass);
        String factoryId = probe.factoryId();

        StreamOperativeTopology topology;
        try {
            topology = StreamOperativeTopologyBuilder.build(factoryId, probe::tasklets);
        } catch (StreamOperativeConfigurationException e) {
            // A workflow reuses the unbounded topology builder, which rejects
            // an empty declaration, dynamic-cell tasklets and unwired
            // channels.
            throw new WorkflowOperativeConfigurationException(
                    "workflow operative `" + factoryId + "` has an invalid topology: " + e.getMessage());
        }
        return new WorkflowSpec(probe, topology, chainOrder(factoryId, topology, entryRoots(factoryId, topology)));
    }

    /**
     * Instantiate the class and refuse an operative that cannot terminate: one
     * whose only possible exit is an external stop, with no duration backstop.
     */
    @Nonnull
    private static WorkflowOperative resolveProbe(Class<? extends WorkflowOperative> operativeClass) {
        WorkflowOperative probe;
        try {
            probe = RuntimeBridge.construct(operativeClass);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "WorkflowOperative class `" + operativeClass.getName() + "` must have a public no-arg constructor: "
                            + e,
                    e);
        }
        String factoryId = probe.factoryId();
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException(
                    "WorkflowOperative class `" + operativeClass.getName() + "` returned null/empty from factoryId()");
        }
        if (!probe.selfTerminating() && probe.maxDuration().isEmpty()) {
            throw new WorkflowOperativeConfigurationException("workflow operative `" + factoryId
                    + "` is not terminable: its only possible exit is an external stop. Add a terminal condition: "
                    + "let the chain complete, have a tasklet call ctx.complete, or declare maxDuration().");
        }
        return probe;
    }

    /** Resolve the retry ladder, rejecting a hot-spinning zero backoff. */
    private static RetryPolicy resolveRetry(String factoryId, WorkflowOperative probe) {
        int attempts = Math.max(1, probe.retryAttempts());
        long backoffMs = probe.retryBackoff().toMillis();
        if (attempts > 1 && backoffMs <= 0) {
            throw WorkflowOperativeConfigurationException.zeroRetryBackoff(factoryId);
        }
        return new RetryPolicy(attempts, backoffMs);
    }

    /**
     * The tasklets the entry channels feed: the roots of the chain each
     * message starts. Every message carries the channel it arrived on and
     * takes that channel's parser and edge transforms, so several entry
     * channels, a transform on an entry edge and an entry channel as a merge
     * source are all ordinary; what a workflow needs is at least one entry
     * channel, with something to feed.
     */
    @Nonnull
    private static List<Integer> entryRoots(String factoryId, StreamOperativeTopology topology) {
        List<Integer> roots = new ArrayList<>();
        for (ChannelDescriptor c : topology.channels()) {
            if (c.kind() != ChannelKind.ENTRY) {
                continue;
            }
            if (c.consumers().isEmpty()) {
                throw new WorkflowOperativeConfigurationException(
                        "workflow operative `" + factoryId + "` declares an entry channel with no consumer");
            }
            for (int consumer : c.consumerIds()) {
                if (!roots.contains(consumer)) {
                    roots.add(consumer);
                }
            }
        }
        if (roots.isEmpty()) {
            throw new WorkflowOperativeConfigurationException("workflow operative `" + factoryId
                    + "` declares tasklets but no entry channel; a structured workflow must declare an input "
                    + "channel (c.input(...)) its messages arrive on");
        }
        return roots;
    }

    /**
     * The execution order of the declared DAG, over the shared {@link
     * GraphShape} walk the stream validation also runs: Kahn order makes the
     * driver's sequential walk dispatch a join only after ALL its producers,
     * with the accumulated batch. Kahn-FIFO equals BFS numbering for a tree, so
     * journaled {@code task_idx} values keep their meaning. Reachability is
     * named before the cycle so a genuinely disconnected tasklet is not
     * reported as a cycle symptom. Tasklet ids are indices into
     * {@code topology.tasklets()}; a channel's {@code consumerIds()} are
     * downstream tasklet ids.
     */
    @Nonnull
    private static List<Integer> chainOrder(String factoryId, StreamOperativeTopology topology, List<Integer> roots) {
        List<int[]> edges = new ArrayList<>();
        for (ChannelDescriptor ch : topology.channels()) {
            if (ch.producer().isPresent()) {
                for (int consumer : ch.consumerIds()) {
                    edges.add(new int[] {ch.producer().getAsInt(), consumer});
                }
            }
        }
        GraphShape.Order shape = GraphShape.order(topology.tasklets().size(), edges, roots);
        if (shape.unreachable() >= 0 && shape.onCycle() < 0) {
            throw new WorkflowOperativeConfigurationException("workflow operative `" + factoryId
                    + "` declares tasklet `"
                    + topology.tasklets().get(shape.unreachable()).name()
                    + "` that is not reachable from any entry channel");
        }
        if (shape.onCycle() >= 0) {
            throw new WorkflowOperativeConfigurationException("workflow operative `" + factoryId
                    + "` declares tasklet `"
                    + topology.tasklets().get(shape.onCycle()).name()
                    + "` on a cycle; a bounded topology is a DAG — a tasklet's output may never"
                    + " reach its own input");
        }
        for (TaskletDescriptor task : topology.tasklets()) {
            // Workflow has no dynamic-cells capability.
            if (task.dynamic()) {
                throw new WorkflowOperativeConfigurationException("workflow operative `" + factoryId
                        + "` declares a dynamic-cell tasklet `" + task.name()
                        + "`; workflow operatives do not support dynamic cell mode");
            }
        }
        return shape.order();
    }
}
