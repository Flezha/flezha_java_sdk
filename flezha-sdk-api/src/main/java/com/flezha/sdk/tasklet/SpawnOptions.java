package com.flezha.sdk.tasklet;

import javax.annotation.Nonnull;

/**
 * The instance a {@link TaskletCtx#spawnWorkflow(String, byte[], SpawnOptions)} request names. Built
 * only from an id, so a policy for a running instance cannot be written for a spawn that has none:
 * without an id the controller generates one and always starts a new instance.
 *
 * <p>Instances are immutable; {@link #ifRunning} answers a new one.
 */
public final class SpawnOptions {

    final String id;
    final IfRunning ifRunning;

    private SpawnOptions(String id, IfRunning ifRunning) {
        this.id = id;
        this.ifRunning = ifRunning;
    }

    /** The instance {@code id}, delivering into it when it is already running. */
    @Nonnull
    public static SpawnOptions id(String id) {
        return new SpawnOptions(id, IfRunning.DELIVER);
    }

    /** What the request does when the instance is already running. */
    @Nonnull
    public SpawnOptions ifRunning(IfRunning ifRunning) {
        return new SpawnOptions(id, ifRunning);
    }
}
