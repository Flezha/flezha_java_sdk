package com.flezha.sdk.runtime.relay;

import com.flezha.sdk.runtime.state.ClockKind;
import com.flezha.sdk.runtime.state.KryoCodec;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javax.annotation.Nonnull;

/**
 * Frozen runtime descriptor for one tasklet node inside a {@link RelayTopology}: what
 * its class declares ({@link TaskletModel}), plus what the wiring decided about the node, its
 * identity, the cell mode the core derived, resolved channel ids (cached at registration so the
 * hot path needs zero name lookups) and encoded types.
 */
public final class TaskletDescriptor {

    private final String name;
    private final TaskletModel model;
    private final boolean dynamic;
    private final OptionalInt inputChannelId;
    private final OptionalInt outputChannelId;
    private final List<Class<?>> kryoTypes;

    public TaskletDescriptor(
            @Nonnull String name,
            @Nonnull TaskletModel model,
            boolean dynamic,
            @Nonnull OptionalInt inputChannelId,
            @Nonnull OptionalInt outputChannelId,
            @Nonnull List<Class<?>> kryoTypes) {
        this.name = name;
        this.model = model;
        this.dynamic = dynamic;
        this.inputChannelId = inputChannelId;
        this.outputChannelId = outputChannelId;
        this.kryoTypes = List.copyOf(kryoTypes);
    }

    /** The node's identity: the tasklet's name, or the explicit node name given at wiring time. */
    @Nonnull
    public String name() {
        return name;
    }

    /** What the node's class declares. */
    @Nonnull
    public TaskletModel model() {
        return model;
    }

    @Nonnull
    @SuppressWarnings("unchecked")
    public Class<? extends Tasklet<?, ?, ?>> taskletClass() {
        return (Class<? extends Tasklet<?, ?, ?>>) model.taskletClass();
    }

    /**
     * The types the node's codec registers: the class's, plus any only the wiring knows (a
     * {@code routeLatest} document class).
     */
    @Nonnull
    public List<Class<?>> kryoTypes() {
        return kryoTypes;
    }

    /** A codec over {@link #kryoTypes()}, with the ids the class's declaration assigns. */
    @Nonnull
    public KryoCodec codec() {
        return model.codec(kryoTypes);
    }

    @Nonnull
    public ClockKind clock() {
        return model.clock();
    }

    @Nonnull
    public List<String> cellKeys() {
        return model.cellKeys();
    }

    public boolean cellsForOverridden() {
        return model.cellsForDeclared();
    }

    /** The declared dynamic-mode cell cap, absent when the class takes the default. */
    @Nonnull
    public OptionalLong maxCells() {
        return model.maxCells();
    }

    /**
     * Whether the core derived dynamic cell mode: a chain keys its values before it reaches this
     * node.
     */
    public boolean dynamic() {
        return dynamic;
    }

    @Nonnull
    public OptionalInt inputChannelId() {
        return inputChannelId;
    }

    @Nonnull
    public OptionalInt outputChannelId() {
        return outputChannelId;
    }
}
