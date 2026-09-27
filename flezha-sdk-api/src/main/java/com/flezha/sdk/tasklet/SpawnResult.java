package com.flezha.sdk.tasklet;

/**
 * A {@link Ctx#spawnBounded} request that reached an instance: the id it resolved to (the
 * caller's, or the one the controller generated) and how it got there. A replayed spawn without an
 * id answers an empty id, because the generated one exists only in the live run's answer.
 */
public record SpawnResult(String id, SpawnOutcome outcome) {}
