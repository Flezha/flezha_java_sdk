package com.flezha.sdk.runtime.operator;

import com.flezha.sdk.state.NoChange;
import com.flezha.sdk.state.RestoreInitPayload;
import com.flezha.sdk.state.WallClock;
import com.flezha.sdk.tasklet.Tasklet;
import java.util.Optional;

/**
 * Test-only base pinning the persistence-related type arguments to their
 * stateless values ({@link NoChange}, {@link WallClock}, {@link Void}) so the
 * runtime-module test fixtures declare only what varies. A subclass that pins
 * {@code Input} / {@code State} / {@code Output} concretely needs no type-token
 * override at all — the interface defaults read all six off this clause.
 */
public abstract class StatelessTestTasklet<Input, State, Output>
        implements Tasklet<Input, State, Output, NoChange, WallClock, Void> {

    /**
     * Default fresh-start initializer that ignores the restore payload — most
     * test tasklets don't care about restore. Subclasses override the variant
     * they need.
     */
    @Override
    public State initState(Optional<String> cellKey, Optional<RestoreInitPayload> initPayload) {
        return initState(cellKey);
    }

    /** Convenience hook for subclasses that don't care about the restore payload. */
    protected abstract State initState(Optional<String> cellKey);
}
