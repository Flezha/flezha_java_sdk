package com.flezha.sdk.tasklet;

import javax.annotation.Nonnull;

/**
 * What {@link Ctx#spawnBounded} does when the requested instance id is
 * already running.
 */
public enum IfRunning {
    /** Deliver the payload to the running instance's inbox. */
    DELIVER("deliver"),
    /** Leave the running instance alone and report {@code operator.already_running}. */
    ERROR("error");

    private final String wire;

    IfRunning(String wire) {
        this.wire = wire;
    }

    /** The policy as the controller reads it off {@code SpawnBoundedRequest}. */
    @Nonnull
    public String wire() {
        return wire;
    }
}
