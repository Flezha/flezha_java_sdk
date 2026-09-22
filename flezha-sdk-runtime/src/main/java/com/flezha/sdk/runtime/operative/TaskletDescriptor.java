package com.flezha.sdk.runtime.operative;

import com.flezha.sdk.state.Change;
import com.flezha.sdk.state.Clock;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.List;
import java.util.OptionalInt;
import javax.annotation.Nonnull;

/**
 * Frozen runtime descriptor for one Tasklet inside a {@link StreamOperativeTopology}.
 *
 * <p>Carries the Tasklet's class so the runtime can instantiate one Tasklet per
 * Cell at operative-instance startup, plus the resolved input/output
 * channel ids (cached at registration time so the hot path needs zero name
 * lookups).
 */
public final class TaskletDescriptor {

    private final String name;
    private final Class<? extends Tasklet<?, ?, ?, ?, ?, ?>> taskletClass;
    private final List<String> cellKeys;
    private final boolean cellsForOverridden;
    private final boolean dynamic;
    private final OptionalInt inputChannelId;
    private final OptionalInt outputChannelId;
    private final Class<?> inputType;
    private final Class<?> outputType;
    private final Class<? extends Change> changeType;
    private final Class<? extends Clock> clockType;
    private final Class<?> configType;
    private final List<Class<?>> kryoTypes;
    private final int schemaVersion;
    private final int minReadableSchemaVersion;
    private final long compactAfterBytes;
    private final int mailboxCapacity;
    private final boolean compensateOverridden;
    private final java.util.Optional<java.time.Duration> cellIdleTtl;

    private final java.util.OptionalLong maxCells;

    public TaskletDescriptor(
            @Nonnull String name,
            @Nonnull Class<? extends Tasklet<?, ?, ?, ?, ?, ?>> taskletClass,
            @Nonnull List<String> cellKeys,
            boolean cellsForOverridden,
            boolean dynamic,
            @Nonnull OptionalInt inputChannelId,
            @Nonnull OptionalInt outputChannelId,
            @Nonnull Class<?> inputType,
            @Nonnull Class<?> outputType,
            @Nonnull Class<? extends Change> changeType,
            @Nonnull Class<? extends Clock> clockType,
            @Nonnull Class<?> configType,
            @Nonnull List<Class<?>> kryoTypes,
            int schemaVersion,
            int minReadableSchemaVersion,
            long compactAfterBytes,
            int mailboxCapacity,
            boolean compensateOverridden,
            @Nonnull java.util.Optional<java.time.Duration> cellIdleTtl,
            @Nonnull java.util.OptionalLong maxCells) {
        this.cellIdleTtl = cellIdleTtl;
        this.maxCells = maxCells;
        this.compensateOverridden = compensateOverridden;
        this.name = name;
        this.taskletClass = taskletClass;
        this.cellKeys = List.copyOf(cellKeys);
        this.cellsForOverridden = cellsForOverridden;
        this.dynamic = dynamic;
        this.inputChannelId = inputChannelId;
        this.outputChannelId = outputChannelId;
        this.inputType = inputType;
        this.outputType = outputType;
        this.changeType = changeType;
        this.clockType = clockType;
        this.configType = configType;
        this.kryoTypes = List.copyOf(kryoTypes);
        this.schemaVersion = schemaVersion;
        this.minReadableSchemaVersion = minReadableSchemaVersion;
        this.compactAfterBytes = compactAfterBytes;
        this.mailboxCapacity = mailboxCapacity;
    }

    @Nonnull
    public Class<? extends Change> changeType() {
        return changeType;
    }

    @Nonnull
    public Class<? extends Clock> clockType() {
        return clockType;
    }

    @Nonnull
    public Class<?> configType() {
        return configType;
    }

    @Nonnull
    public List<Class<?>> kryoTypes() {
        return kryoTypes;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    /** The oldest schema version this Tasklet still reads. */
    public int minReadableSchemaVersion() {
        return minReadableSchemaVersion;
    }

    public long compactAfterBytes() {
        return compactAfterBytes;
    }

    @Nonnull
    public String name() {
        return name;
    }

    @Nonnull
    public Class<? extends Tasklet<?, ?, ?, ?, ?, ?>> taskletClass() {
        return taskletClass;
    }

    @Nonnull
    public List<String> cellKeys() {
        return cellKeys;
    }

    public boolean cellsForOverridden() {
        return cellsForOverridden;
    }

    /**
     * Whether the Tasklet declares a {@code compensate} hook. Read by the
     * declaration map's {@code compensable_tasks}: the controller refuses to
     * rewind a tasklet whose plugin output is not semantically undoable, so
     * it needs this before it wakes the instance.
     */
    public boolean compensateOverridden() {
        return compensateOverridden;
    }

    /** Idle TTL for a dynamic Cell; empty = eviction off. */
    @Nonnull
    public java.util.Optional<java.time.Duration> cellIdleTtl() {
        return cellIdleTtl;
    }

    /** The declared dynamic-mode Cell cap, absent when the class takes the default. */
    public java.util.OptionalLong maxCells() {
        return maxCells;
    }

    /**
     * Whether some consumer edge into this node declares a {@code keyBy} —
     * dynamic Cell mode is read off the WIRING, not off the tasklet.
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

    @Nonnull
    public Class<?> inputType() {
        return inputType;
    }

    @Nonnull
    public Class<?> outputType() {
        return outputType;
    }

    /** Resolved per-Cell mailbox capacity (Tasklet override → env var → default). */
    public int mailboxCapacity() {
        return mailboxCapacity;
    }
}
