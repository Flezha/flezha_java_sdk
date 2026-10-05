package com.flezha.sdk.runtime.relay;

import com.flezha.sdk.relay.Relay.Durable;
import com.flezha.sdk.relay.Relay.Exactly;
import com.flezha.sdk.relay.Relay.Run;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Frozen, immutable per-class topology produced by
 * {@link com.flezha.sdk.runtime.relay.builder.RelayTopologyBuilder} at
 * {@code FlezhaRunner.registerFactory(Class)}
 * time. Shared across every instance of the class.
 *
 * <p>The hot path (entry-channel input → consumer-tasklet cell) reads
 * {@link #channels} by index after one {@link #nameToId} lookup; the
 * inter-tasklet {@code emit} hot path reads {@link #channels} by the
 * pre-cached {@code outputChannelId} stamped on the {@code TaskletDispatcher}
 * at build time.
 */
public final class RelayTopology {

    private final String factoryId;
    private final List<ChannelDescriptor> channels;
    private final Map<String, Integer> nameToId;
    private final List<TaskletDescriptor> tasklets;
    private final Map<String, Integer> taskletNameToId;
    private final Run run;
    private final Durable durable;
    private final Map<String, Object> declaration;

    public RelayTopology(
            @Nonnull String factoryId,
            @Nonnull List<ChannelDescriptor> channels,
            @Nonnull Map<String, Integer> nameToId,
            @Nonnull List<TaskletDescriptor> tasklets,
            @Nonnull Map<String, Integer> taskletNameToId,
            @Nonnull Run run,
            @Nonnull Durable durable,
            @Nonnull Map<String, Object> declaration) {
        this.run = run;
        this.durable = durable;
        this.declaration = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(declaration));
        this.factoryId = factoryId;
        this.channels = List.copyOf(channels);
        this.nameToId = Map.copyOf(nameToId);
        this.tasklets = List.copyOf(tasklets);
        this.taskletNameToId = Map.copyOf(taskletNameToId);
    }

    /** Whether the class runs in steps: its stages are held and journaled rather than routed. */
    public boolean steps() {
        return run instanceof Run.Steps;
    }

    /**
     * Whether each invocation's output rides one effect record the controller
     * stamps at flush: under the effectively-once tier, and for a durable class
     * that runs in steps, whose stage commit names the record.
     */
    public boolean recordsEffects() {
        return switch (run) {
            case Run.Flow flow -> flow.exactly() instanceof Exactly.EffectivelyOnce;
            case Run.Steps ignored -> !(durable instanceof Durable.Memory);
        };
    }

    /**
     * The {@code HostClassDecl} record this class was built from — what the
     * builder was told, submitted to the core's one check and re-sent
     * verbatim at declare time. Written once, so the bytes the core judged
     * and the bytes it registers cannot drift.
     */
    @Nonnull
    public Map<String, Object> declaration() {
        return declaration;
    }

    @Nonnull
    public String factoryId() {
        return factoryId;
    }

    @Nonnull
    public List<ChannelDescriptor> channels() {
        return channels;
    }

    @Nonnull
    public Map<String, Integer> nameToId() {
        return nameToId;
    }

    @Nonnull
    public List<TaskletDescriptor> tasklets() {
        return tasklets;
    }

    @Nonnull
    public Map<String, Integer> taskletNameToId() {
        return taskletNameToId;
    }

    public ChannelDescriptor channel(int id) {
        if (id < 0 || id >= channels.size()) {
            return null;
        }
        return channels.get(id);
    }

    public TaskletDescriptor tasklet(int id) {
        if (id < 0 || id >= tasklets.size()) {
            return null;
        }
        return tasklets.get(id);
    }

    public Integer lookupChannelId(@Nonnull String name) {
        return nameToId.get(name);
    }
}
